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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.control.http

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.SecurityScope
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.control.sdk.ChangeState
import me.ahoo.cosec.control.sdk.ConflictKind
import me.ahoo.cosec.control.sdk.ControlCaller
import me.ahoo.cosec.control.sdk.ControlRefusedException
import me.ahoo.cosec.document.identity.IdentityCodec
import me.ahoo.cosec.document.identity.IdentityHeader
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.testkit.PermittedRequest
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [HttpControlService] on the wire (`CTL-77`): each call is the route's request, the caller stated in `CoSec-Identity`
 * as the gateway forwards a request's principal or as the in-process implementation admits a service, and a refusal is
 * the route's problem. What a host answers is held to the in-process implementation's by the starter's parity suite.
 * A call that never ends fails its test rather than holding the build (a mutant without the timeout is killed so).
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class HttpControlServiceTest {
    /** What the server received, one line per request: method, raw path and query, body. */
    private val received = mutableListOf<String>()
    private val identities = mutableListOf<String?>()
    private val contentTypes = mutableListOf<String?>()
    private var answer: Pair<Int, String> = 200 to "{}"

    /** Whether the server holds each request until [release] opens, instead of answering at once. */
    @Volatile
    private var hold = false
    private val release = CountDownLatch(1)

    /** Set as the server sends a response: what proves that a call ended before the server answered it. */
    private val answered = AtomicBoolean(false)

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            synchronized(received) {
                received += "${exchange.requestMethod} ${exchange.requestURI.rawPath}" +
                    (exchange.requestURI.rawQuery?.let { "?$it" } ?: "") + if (body.isEmpty()) "" else " $body"
                identities += exchange.requestHeaders.getFirst(IdentityHeader.NAME)
                contentTypes += exchange.requestHeaders.getFirst("Content-Type")
            }
            if (hold) release.await(HELD_AT_MOST.toMillis(), TimeUnit.MILLISECONDS)
            answered.set(true)
            val (status, text) = answer
            val bytes = text.encodeToByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            if (status in 300..399) exchange.responseHeaders.add("Location", "http://elsewhere.example/")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    private val service = HttpControlService(URI("http://127.0.0.1:${server.address.port}/cosec/control/v1/"))
    private val iam = ControlCaller.service("svc-iam", setOf("projector"))
    private val acme = SnapshotKey.Tenant("acme")

    @AfterEach
    fun stop() {
        // A held request is let go first: the server's one handler thread is then free, and stop is prompt.
        release.countDown()
        server.stop(0)
    }

    @Test
    fun `each call is the route's request`() {
        runBlocking {
            service.documents(iam, DocumentKind.TENANT, "n0", 5)
            service.document(iam, SnapshotKey.Tenant("a b%"))
            service.revisions(iam, acme, 3, 2)
            service.revision(iam, acme, 2)
            service.diff(iam, acme, 1, 2)
            service.apply(iam, acme, """{"mode":"MERGE"}""")
            service.propose(iam, """{"document":"tenant:acme"}""")
            service.check(iam, """{"document":"tenant:acme"}""")
            service.checkApply(iam, acme, """{"mode":"MERGE"}""")
            service.change(iam, "c 1")
            service.changes(iam, acme, ChangeState.READY, "a-b", 10)
            service.rebase(iam, "c1")
            service.startShadow(iam, "c1")
            service.skipShadow(iam, "c1", "a \"reason\"")
            service.abandon(iam, "c1")
            service.publish(iam, "c1")
            service.publish(iam, "c1", """{"ack":{"window":"w","counts":{}}}""")
        }
        received.assert().containsExactly(
            "GET /cosec/control/v1/documents?kind=tenant&after=n0&limit=5",
            "GET /cosec/control/v1/documents/tenant:a%20b%25",
            "GET /cosec/control/v1/documents/tenant:acme/revisions?before=3&limit=2",
            "GET /cosec/control/v1/documents/tenant:acme/revisions/2",
            "GET /cosec/control/v1/documents/tenant:acme/diff?from=1&to=2",
            """POST /cosec/control/v1/documents/tenant:acme:apply {"mode":"MERGE"}""",
            """POST /cosec/control/v1/changes {"document":"tenant:acme"}""",
            """POST /cosec/control/v1/changes:check {"document":"tenant:acme"}""",
            """POST /cosec/control/v1/documents/tenant:acme:check {"mode":"MERGE"}""",
            "GET /cosec/control/v1/changes/c%201",
            "GET /cosec/control/v1/changes?document=tenant%3Aacme&state=READY&after=a-b&limit=10",
            "POST /cosec/control/v1/changes/c1:rebase {}",
            "POST /cosec/control/v1/changes/c1:shadow {}",
            """POST /cosec/control/v1/changes/c1:skip-shadow {"reason":"a \"reason\""}""",
            "POST /cosec/control/v1/changes/c1:abandon {}",
            "POST /cosec/control/v1/changes/c1:publish {}",
            """POST /cosec/control/v1/changes/c1:publish {"ack":{"window":"w","counts":{}}}""",
        )
        // A body is JSON; a read has none.
        contentTypes.first().assert().isNull()
        contentTypes[5].assert().isEqualTo("application/json")
    }

    @Test
    fun `a service caller is stated as the identity the in-process implementation admits`() {
        val before = Instant.now()
        runBlocking { service.document(iam, acme) }
        val identity = IdentityCodec.decode(checkNotNull(identities.single()))
        identity.tenant.assert().isEqualTo("(platform)")
        identity.subject.assert().isEqualTo("svc-iam")
        identity.app.assert().isEqualTo(HttpControlService.CONTROL_APP)
        identity.roles.assert().containsExactly("projector")
        identity.credential.method.assert().isEqualTo(Identity.Method.SERVICE)
        identity.credential.id.assert().isEqualTo("service:svc-iam")
        identity.issuedAt.assert().isAfterOrEqualTo(before.minusSeconds(1))
        checkNotNull(identity.credential.expiresAt).assert().isBefore(Instant.now().plus(Duration.ofMinutes(1)))
        // A header per call: a new request id each time.
        runBlocking { service.document(iam, acme) }
        IdentityCodec.decode(checkNotNull(identities[1])).requestId.assert().isNotEqualTo(identity.requestId)
    }

    @Test
    fun `a request's caller is stated as the gateway forwards it`() {
        val now = Instant.now()
        val forwarded = Identity(
            tenant = "acme",
            subject = "alice",
            app = "cosec-control",
            space = "",
            roles = setOf("admin"),
            credential = Identity.Credential(Identity.Method.JWT, "token-1", now.plusSeconds(300)),
            issuedAt = now,
            requestId = "r1",
        )
        val context = PermittedRequest.withIdentity(IdentityHeader.write(forwarded))
        runBlocking { service.document(ControlCaller.of(context), acme) }
        val identity = IdentityCodec.decode(checkNotNull(identities.single()))
        identity.tenant.assert().isEqualTo("acme")
        identity.subject.assert().isEqualTo("alice")
        identity.roles.assert().containsExactly("admin")
        identity.credential.id.assert().isEqualTo("token-1")
        identity.credential.method.assert().isEqualTo(Identity.Method.JWT)
    }

    @Test
    fun `a caller that cannot be stated is refused before anything is sent`() {
        val assembled = object : SecurityContext {
            override val tenantId = "acme"
            override val principal: CoSecPrincipal = object : CoSecPrincipal {
                override val id = "alice"
                override val authenticated = true
                override val attributes: Map<String, Any> = emptyMap()
            }
            override val scope: SecurityScope get() = error("not read")
            override val forwardedIdentity = null
        }
        assertThrownBy<IllegalArgumentException> { runBlocking { service.document(ControlCaller.of(assembled), acme) } }
            .hasMessage(ControlCaller.NOT_COSEC_CONTEXT)
        // CoSec made it, but no credential stands behind it: never sent as anonymous.
        val credentialless = PermittedRequest.forSubject("alice", "acme")
        assertThrownBy<IllegalArgumentException> {
            runBlocking { service.document(ControlCaller.of(credentialless), acme) }
        }.hasMessageContaining("no credential")
        received.assert().isEmpty()
    }

    /** DR1-F-2, `PIP-26`: a request that has ended is not stated: refused as the host refuses, `403` without detail. */
    @Test
    fun `an ended request's caller is refused before anything is sent`() {
        val now = Instant.now()
        val forwarded = Identity(
            tenant = "acme",
            subject = "alice",
            app = "cosec-control",
            space = "",
            roles = setOf("admin"),
            credential = Identity.Credential(Identity.Method.JWT, "token-1", now.plusSeconds(300)),
            issuedAt = now,
            requestId = "r1",
        )
        val context = PermittedRequest.withIdentity(IdentityHeader.write(forwarded))
        val security = me.ahoo.cosec.runtime.http.RequestSecurity.of(context)!!
        security.end()

        val refused = assertThrownBy<ControlRefusedException> {
            runBlocking { service.document(ControlCaller.of(context), acme) }
        }.actual()

        refused.status.assert().isEqualTo(403)
        refused.code.assert().isEqualTo("FORBIDDEN")
        refused.detail.assert().isNull()
        refused.problem.assert().contains("\"requestId\":\"${security.requestId}\"")
        received.assert().isEmpty()
    }

    @Test
    fun `a refusal is the route's problem`() {
        val problem = """{"type":"https://cosec.ahoo.me/problems/conflict","status":409,"code":"CONFLICT",""" +
            """"conflict":"SLOT_HELD","heldBy":"c0"}"""
        answer = 409 to problem
        val refused = assertThrownBy<ControlRefusedException> { runBlocking { service.skipShadow(iam, "c1", "r") } }
            .actual()
        refused.status.assert().isEqualTo(409)
        refused.problem.assert().isEqualTo(problem)
        refused.conflict.assert().isEqualTo(ConflictKind.SLOT_HELD)
        refused.heldBy.assert().isEqualTo("c0")
        answer = 201 to """{"id":"c2"}"""
        runBlocking { service.propose(iam, "{}") }.assert().isEqualTo("""{"id":"c2"}""")
    }

    @Test
    fun `only a 2xx is an answer`() {
        // A redirect the client does not follow is no answer of the route's.
        answer = 302 to ""
        assertThrownBy<ControlRefusedException> { runBlocking { service.document(iam, acme) } }.actual()
            .status.assert().isEqualTo(302)
        // A proxy's error page: the status and the body as they came, with no problem member to read.
        answer = 502 to "<html>bad gateway</html>"
        val refused = assertThrownBy<ControlRefusedException> { runBlocking { service.document(iam, acme) } }.actual()
        refused.status.assert().isEqualTo(502)
        refused.problem.assert().isEqualTo("<html>bad gateway</html>")
        refused.code.assert().isNull()
        refused.conflict.assert().isNull()
    }

    /**
     * The call ends because of its own timeout, before the server answered: no bound in seconds around it, which would
     * measure the first call's warm-up of a cold JVM (the identity's codec, the client's selector thread, coroutines)
     * on a loaded runner as much as the timeout. Without a timeout the call waits until the server lets the request go
     * ([HELD_AT_MOST]) and is answered, so no `HttpTimeoutException` comes (the mutant `ctl-77-http-call-waits-forever`);
     * a call that never ended would meet the class's `@Timeout`.
     */
    @Test
    fun `a call that is not answered within its timeout ends`() {
        hold = true
        val quick = HttpControlService(
            URI("http://127.0.0.1:${server.address.port}/cosec/control/v1"),
            timeout = Duration.ofMillis(200),
        )
        assertThrownBy<HttpTimeoutException> { runBlocking { quick.document(iam, acme) } }
        answered.get().assert().describedAs("the server had answered before the call ended").isFalse()
        // Whether the request had reached the server when the timeout fired is not part of the property, and a loaded
        // runner does not promise it: the call may end before the server records it. What holds whatever the timing is
        // that the one call was never sent twice.
        synchronized(received) { received.size.assert().isLessThanOrEqualTo(1) }
        HttpControlService.DEFAULT_TIMEOUT.assert().isEqualTo(Duration.ofSeconds(30))
        assertThrownBy<IllegalArgumentException> { HttpControlService(URI("http://host/x"), timeout = Duration.ZERO) }
    }

    @Test
    fun `a transport failure is the client's own exception`() {
        // A port nothing listens on: the connection is refused at once, never waited out.
        val port = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
        val nowhere = HttpControlService(URI("http://127.0.0.1:$port/cosec/control/v1"))
        val failure = assertThrownBy<IOException> { runBlocking { nowhere.document(iam, acme) } }.actual()
        failure.assert().isInstanceOf(ConnectException::class.java)
    }

    @Test
    fun `the control API's base is an absolute http URI without a query`() {
        listOf("relative/path", "ftp://host/x", "http://host/x?a=1").forEach {
            assertThrownBy<IllegalArgumentException> { HttpControlService(URI(it)) }
        }
    }

    private companion object {
        /** The longest the server holds a request: longer than any test's call, shorter than the class's timeout. */
        val HELD_AT_MOST: Duration = Duration.ofSeconds(15)
    }
}
