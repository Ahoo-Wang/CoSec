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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.spring.boot.pipeline

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.PolicyDocumentCompiler
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.document.route.Route
import me.ahoo.cosec.document.route.RouteMap
import me.ahoo.cosec.engine.CompiledDocument
import me.ahoo.cosec.runtime.snapshot.SnapshotOptions
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * UC-1 on the host: one WARN per installed revision whose `http:` statements a mapped route escapes, with the count and
 * the first five, the whole list at DEBUG; nothing again for a revision already reviewed; memory bounded by capacity.
 */
class RouteEscapeReviewTest {
    private val routes = RouteMap.of(
        (1..7).map { RouteMap.Located(Route("DELETE", "/admin/r$it/{id}", "wms:r$it.purge", "r$it/{id}"), "wms.json") },
    )

    private fun compile(key: SnapshotKey, revision: Long, policies: String): CompiledDocument =
        PolicyDocumentCompiler().compile(
            key,
            SnapshotDocuments.read(
                (
                    """{"schema":2,"revision":$revision,"publishedAt":"2026-11-01T00:00:00Z","policies":$policies,""" +
                        """"bindings":[],"rateLimits":[]}"""
                    ).encodeToByteArray()
            ),
        )

    private val denyAdmin =
        """[{"id":"deny-admin","statements":[{"effect":"deny","action":"http:*","resource":"http:path:/admin/**"}]}]"""

    private fun <T> logged(block: (ListAppender<ILoggingEvent>) -> T): T {
        val logger = LoggerFactory.getLogger(RouteEscapeReview::class.java) as Logger
        val level = logger.level
        logger.level = Level.DEBUG
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try {
            return block(appender)
        } finally {
            logger.detachAppender(appender)
            logger.level = level
        }
    }

    private fun ListAppender<ILoggingEvent>.at(level: Level) = list.filter { it.level == level }

    @Test
    fun `one WARN per revision, with the count and the first five, every escape at DEBUG`() {
        val review = RouteEscapeReview(routes, capacity = 8)
        logged { appender ->
            review.installed(SnapshotKey.Platform, 1, compile(SnapshotKey.Platform, 1, denyAdmin))
            val warn = appender.at(Level.WARN).single().formattedMessage
            warn.assert().contains("[platform] revision 1: 7 mapped route(s)")
            warn.assert().contains("DELETE /admin/r5/{id}")
            warn.assert().doesNotContain("/admin/r6/")
            warn.assert().contains("and 2 more at DEBUG")
            appender.at(Level.DEBUG).single().formattedMessage.assert().contains("DELETE /admin/r7/{id}")

            // The same revision again (a forced reload, a recompile, a restart of the loop): nothing.
            review.installed(SnapshotKey.Platform, 1, compile(SnapshotKey.Platform, 1, denyAdmin))
            appender.at(Level.WARN).size.assert().isEqualTo(1)
            // The next revision is reviewed.
            review.installed(SnapshotKey.Platform, 2, compile(SnapshotKey.Platform, 2, denyAdmin))
            appender.at(Level.WARN).size.assert().isEqualTo(2)
        }
    }

    @Test
    fun `a document whose http statements no route escapes, or with none, logs nothing`() {
        val review = RouteEscapeReview(routes, capacity = 8)
        logged { appender ->
            val tenant = SnapshotKey.Tenant("acme")
            review.installed(
                tenant,
                1,
                compile(tenant, 1, """[{"id":"p","statements":[{"effect":"allow","action":"order:read"}]}]""")
            )
            review.installed(
                SnapshotKey.Platform,
                1,
                compile(
                    SnapshotKey.Platform,
                    1,
                    """[{"id":"both","statements":[{"effect":"deny","action":["http:*","wms:*"],""" +
                        """"resource":["http:path:/admin/**","r1/**","r2/**","r3/**","r4/**","r5/**","r6/**",""" +
                        """"r7/**"]}]}]""",
                ),
            )
            appender.list.assert().isEmpty()
        }
    }

    @Test
    fun `what it remembers is bounded, and a key it forgot is reviewed again`() {
        val review = RouteEscapeReview(routes, capacity = 2)
        logged { appender ->
            val keys = listOf(SnapshotKey.App("a"), SnapshotKey.App("b"), SnapshotKey.App("c"))
            // What the review remembers is by key: one compiled document serves every key.
            val document = compile(SnapshotKey.Platform, 1, denyAdmin)
            keys.forEach { review.installed(it, 1, document) }
            review.remembered.assert().isEqualTo(2)
            appender.at(Level.WARN).size.assert().isEqualTo(3)
            // "a" was the eldest and is forgotten, as a key the source evicts and installs again.
            review.installed(keys[0], 1, document)
            appender.at(Level.WARN).size.assert().isEqualTo(4)
            review.installed(keys[2], 1, document)
            appender.at(Level.WARN).size.assert().isEqualTo(4)
        }
    }

    @Test
    fun `a key that leaves the source is forgotten, and reviewed again when it is back`() {
        val review = RouteEscapeReview(routes, capacity = 8)
        val document = compile(SnapshotKey.Platform, 1, denyAdmin)
        val key = SnapshotKey.App("a")
        logged { appender ->
            review.installed(key, 1, document)
            review.remembered.assert().isEqualTo(1)
            review.removed(key)
            review.remembered.assert().isEqualTo(0)
            review.installed(key, 1, document)
            appender.at(Level.WARN).size.assert().isEqualTo(2)
        }
    }

    @Test
    fun `a host without a route map has no review, and a host with one remembers as many keys as the source holds`() {
        val options = SnapshotOptions(maxApps = 3, maxTenants = 4)
        RouteEscapeReview.of(null, options).assert().isEmpty()
        RouteEscapeReview.of(RouteMap.of(emptyList()), options).assert().isEmpty()
        val review = RouteEscapeReview.of(routes, options).single() as RouteEscapeReview
        val document = compile(SnapshotKey.Platform, 1, denyAdmin)
        logged { (1..20).forEach { review.installed(SnapshotKey.App("app-$it"), 1, document) } }
        review.remembered.assert().isEqualTo(3 + 4 + 1)
    }
}
