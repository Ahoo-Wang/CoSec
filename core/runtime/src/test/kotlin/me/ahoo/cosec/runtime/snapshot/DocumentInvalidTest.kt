/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(ExperimentalCoSecApi::class, InternalCoSecApi::class)

package me.ahoo.cosec.runtime.snapshot

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.event.DecisionEvent
import me.ahoo.cosec.runtime.event.DecisionListener
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.http.ProblemEncoder
import me.ahoo.cosec.runtime.http.StatusMapping
import me.ahoo.cosec.runtime.http.Unavailability
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Drill entry 26: a stored document that does not compile. Every request that needs it fails closed with the cause
 * `DocumentInvalid` at its stored revision, which the decision event's view version carries too; the log says so once
 * per revision and what it compiled against, and once more when a revision compiles; and the client's 503 is exactly
 * what an unreachable store answers, since the compiler's message names policy content.
 */
class DocumentInvalidTest {
    private val store = InMemorySnapshotDocumentStore()
    private val revisions = HashMap<SnapshotKey, Long>()

    /** The source writes its lines off the request's thread while the test reads them: a list both can share. */
    private val logged = CopyOnWriteArrayList<ILoggingEvent>()
    private val appender = object : AppenderBase<ILoggingEvent>() {
        override fun append(event: ILoggingEvent) {
            logged += event
        }
    }.apply { start() }
    private val logger = LoggerFactory.getLogger(SnapshotSource::class.java) as Logger

    @BeforeEach
    fun attach() {
        logger.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(appender)
    }

    private fun publish(key: SnapshotKey, body: String) {
        val revision = (revisions[key] ?: 0) + 1
        revisions[key] = revision
        val text = """{"schema":2,"revision":$revision,"publishedAt":"2026-10-26T00:00:00Z"$body}"""
        store.put(key, SnapshotDocuments.read(text.encodeToByteArray()))
    }

    /** A tenant document that reads an attribute nothing declares: it does not compile. */
    private val broken = ""","policies":[{"id":"fenced","statements":[{"effect":"allow","action":"order:read",""" +
        """"condition":{"eq":["ext.geo.inFence",true]}}]}],""" +
        """"bindings":[{"id":"t","subject":"*","app":"*","space":"*","policy":"fenced"}]"""

    private fun lines(level: Level, text: String) = logged.filter {
        it.level == level && text in it.formattedMessage
    }

    /** The log lines are written off the request's thread: wait until [count] of them are there. */
    private fun awaitLines(level: Level, text: String, count: Int): List<ILoggingEvent> {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (lines(level, text).size < count) {
            check(
                System.nanoTime() < deadline
            ) { "No $count $level lines with [$text]: ${logged.map { it.formattedMessage }}" }
            Thread.sleep(5)
        }
        return lines(level, text)
    }

    @Test
    fun `a document that does not compile is logged once per revision, and its recovery once`() {
        publish(SnapshotKey.Platform, "")
        publish(SnapshotKey.Tenant("acme"), broken)
        val compiler = PlatformCompiler()
        DocumentSnapshots.source(store, compiler).use { source ->
            compiler.bind(source)
            source.load().join()
            val tenant = SnapshotKey.Tenant("acme")
            source.reload(tenant).join().invalid.assert().isTrue()
            val first = awaitLines(Level.ERROR, "does not compile", 1).single()
            first.formattedMessage.assert()
                .contains(
                    "[$tenant] revision 1 does not compile against the limits and attributes of platform revision 1"
                )
                .contains("fails closed (503)")
            // A reload of the same revision, against the same inputs, logs nothing again.
            source.reload(tenant).join()
            source.reload(tenant).join()
            Thread.sleep(50)
            lines(Level.ERROR, "does not compile").size.assert().isEqualTo(1)
            // Another revision that does not compile either: once more.
            publish(tenant, broken)
            source.reload(tenant).join().revision.assert().isEqualTo(2)
            awaitLines(Level.ERROR, "does not compile", 2).last().formattedMessage.assert().contains("revision 2")
            // One that compiles: the fault ends, once.
            publish(tenant, "")
            source.reload(tenant).join().invalid.assert().isFalse()
            awaitLines(Level.INFO, "compiles", 1).single().formattedMessage.assert().contains("[$tenant] revision 3")
            source.reload(tenant).join()
            Thread.sleep(50)
            lines(Level.INFO, "compiles").size.assert().isEqualTo(1)
        }
    }

    @Test
    fun `a decision event says the document is invalid, at the revision its view carries`() = runBlocking<Unit> {
        publish(SnapshotKey.Platform, ""","policies":[{"id":"perm:x/y","statements":[]}]""")
        val events = CopyOnWriteArrayList<DecisionEvent>()
        val listener = object : DecisionListener {
            override val name = "events"

            override suspend fun onDecision(event: DecisionEvent) {
                events += event
            }
        }
        Pipeline.builder(Authenticator { AuthnResult.Anonymous }, store).listener(listener).build().use { pipeline ->
            pipeline.awaitDocuments()
            val outcome = pipeline.evaluate(
                HttpFacts("GET", "/orders/1", null, Headers.EMPTY, Cookies.EMPTY, "10.0.0.1", HostScope(null)),
            )
            outcome.status.assert().isEqualTo(503)
            val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
            while (events.isEmpty()) {
                check(System.nanoTime() < deadline) { "no decision event" }
                Thread.sleep(5)
            }
            val event = events.single()
            val cause = event.outcome.cause as Cause.DocumentInvalid
            cause.document.assert().isEqualTo(DocumentKind.PLATFORM)
            cause.revision.assert().isEqualTo(1L)
            event.snapshotVersion?.platform.assert().isEqualTo(cause.revision)
        }
    }

    /** Nothing new reaches the client: the 503 of an invalid document is, byte for byte, an unreachable store's. */
    @Test
    fun `the client's answer is the same as for an unavailable source`() {
        val error = IllegalStateException("$.policies[0].statements[0]: the secret policy content")
        val invalid = StatusMapping.unavailable(
            "r1",
            Unavailability.Indeterminate(Cause.DocumentInvalid(DocumentKind.PLATFORM, 7, error)),
            null,
        )
        val unavailable = StatusMapping.unavailable(
            "r1",
            Unavailability.Indeterminate(Cause.SourceUnavailable(DocumentKind.PLATFORM, error)),
            null,
        )
        invalid.status.assert().isEqualTo(503).isEqualTo(unavailable.status)
        val body = ProblemEncoder.encode(invalid.problem!!)
        body.assert().isEqualTo(ProblemEncoder.encode(unavailable.problem!!))
        invalid.headers.assert().isEqualTo(unavailable.headers)
        String(body).assert().doesNotContain("secret").doesNotContain("policies")
    }
}
