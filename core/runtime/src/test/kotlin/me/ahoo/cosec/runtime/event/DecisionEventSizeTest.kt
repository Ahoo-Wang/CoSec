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

package me.ahoo.cosec.runtime.event

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.document.route.RouteMapCodec
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.HeapBudget
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.PipelineFixtures
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.openjdk.jol.info.GraphLayout
import java.io.File

/**
 * `OBS-11`: what one queued decision event retains at each trace level, measured with JOL. The documents are a
 * representative deployment's: the platform document of `wiki/examples/app`, an app with a catalog of 100 permissions in
 * five groups and twelve system roles, and a tenant with twelve custom roles of twenty permissions and twenty role
 * bindings; the requests are an authenticated principal's, holding three roles in a space, through the app's route map,
 * each its own path and request id, with a gateway's usual headers, and denied: the upper case of a trace, which then
 * holds every candidate the request meets (97). What one event retains of its own is the difference between
 * [MEASURED] and 2 × [MEASURED] events held: what they all share (the compiled documents a trace points into, the
 * decision's statement) cancels out. The listener queues count events with [HeapBudget.decisionEventBytes] for the
 * pipeline's level, once, at startup: this test holds each constant to its measurement, never below it and at most twice
 * it.
 */
class DecisionEventSizeTest {
    private class Keep : DecisionListener {
        override val name = "keep"
        override val delivery = Delivery.REQUIRED
        val events = ArrayList<DecisionEvent>()

        override suspend fun onDecision(event: DecisionEvent) {
            synchronized(events) { events += event }
        }
    }

    private fun events(level: TraceLevel): List<DecisionEvent> {
        val keep = Keep()
        val store = InMemorySnapshotDocumentStore().apply {
            put(SnapshotKey.Platform, document(platformDocument()))
            put(SnapshotKey.App(APP), document(appDocument()))
            put(SnapshotKey.Tenant(PipelineFixtures.TENANT.value), document(tenantDocument()))
        }
        val principal = PipelineFixtures.authenticated(
            PipelineFixtures.claims("warehouse-1" to "role-3", "warehouse-1" to "role-7", "warehouse-2" to "custom-2"),
            app = APP,
        )
        Pipeline.builder(PipelineFixtures.authenticator(principal), store)
            .options { traceLevel = level }
            .routes(ROUTES)
            .snapshots { prewarmTenants = setOf(PipelineFixtures.TENANT.value) }
            .listener(keep)
            .build().use { pipeline ->
                runBlocking {
                    pipeline.awaitDocuments()
                    repeat(2 * MEASURED) {
                        pipeline.evaluate(
                            PipelineFixtures.facts(
                                path = "/items/$it",
                                query = "page=$it&size=20",
                                host = HostScope(AppId(APP)),
                                headers = mapOf(
                                    "User-Agent" to listOf("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131.0"),
                                    "X-Forwarded-For" to listOf("203.0.113.${it % 250}, 10.0.0.1"),
                                    "CoSec-Device-Id" to listOf("device-$it"),
                                    "CoSec-Request-Id" to listOf("request-$it"),
                                    "CoSec-Space-Id" to listOf("warehouse-1"),
                                ),
                            )
                        )
                    }
                }
            }
        return keep.events
    }

    @ParameterizedTest
    @EnumSource(TraceLevel::class)
    fun `the constant a queue counts with is what a decision event retains at its trace level, within a factor of two`(
        level: TraceLevel,
    ) {
        val events = events(level)
        events.size.assert().isEqualTo(2 * MEASURED)
        events.all { (it.evaluationTrace != null) == (level != TraceLevel.OFF) }.assert().isTrue()
        val half = GraphLayout.parseInstance(*events.take(MEASURED).toTypedArray()).totalSize()
        val all = GraphLayout.parseInstance(*events.toTypedArray()).totalSize()
        val perEvent = (all - half) / MEASURED
        val constant = HeapBudget.decisionEventBytes(level)
        val traced = events.first().evaluationTrace?.candidates?.size ?: 0
        println("DecisionEvent at $level: $traced candidates traced, $perEvent B retained; the constant is $constant B")
        // The upper case: a denied request traces every candidate it meets.
        events.all { it.outcome.status == 403 }.assert().isTrue()
        constant.assert().isGreaterThanOrEqualTo(perEvent).isLessThanOrEqualTo(perEvent * 2)
    }

    private companion object {
        const val MEASURED = 200
        const val APP = "wms"
        const val PUBLISHED = "2026-11-01T08:00:00Z"

        val ROUTES = RouteMapCodec.load(
            listOf(
                "cosec-routes.json" to """
                    {"service":"wms","routes":[
                      {"method":"GET","path":"/items/{id}","action":"wms:g1.p4","resource":"res-4/{id}"}]}
                """.trimIndent().encodeToByteArray(),
            ),
        )

        fun document(json: String) = SnapshotDocuments.read(json.encodeToByteArray())

        fun platformDocument(): String =
            File("../../wiki/examples/app/documents/platform.json").takeIf { it.exists() }?.readText()
                ?: File("wiki/examples/app/documents/platform.json").readText()

        fun appDocument(): String {
            val groups = (0 until 5).joinToString(",") { g ->
                val permissions = (0 until 20).joinToString(",") { i ->
                    val resource = if (i % 4 == 0) ""","resource":"res-$i/{id}"""" else ""
                    """{"id":"g$g.p$i","name":"Permission $g.$i","action":"$APP:g$g.p$i"$resource}"""
                }
                """{"name":"Group $g","permissions":[$permissions]}"""
            }
            val roles = (0 until 12).joinToString(",") { r ->
                val permissions = (0 until minOf(100, r * 9)).joinToString(
                    ",",
                    "[",
                    "]"
                ) { "\"g${it / 20}.p${it % 20}\"" }
                """{"id":"role-$r","name":"Role $r","permissions":$permissions}"""
            }
            return """{"schema":2,"revision":1,"publishedAt":"$PUBLISHED","catalog":{"groups":[$groups]},"roles":[$roles]}"""
        }

        fun tenantDocument(): String {
            val roles = (0 until 12).joinToString(",") { k ->
                val permissions = (0 until 20).joinToString(",", "[", "]") { j ->
                    val p = (k * 20 + j) % 100
                    "\"g${p / 20}.p${p % 20}\""
                }
                """{"id":"custom-$k","app":"$APP","name":"Custom role $k","permissions":$permissions}"""
            }
            val bindings = (0 until 20).joinToString(",") { j ->
                val p = (j * 5) % 100
                """{"id":"b-$j","subject":{"role":"role-${j % 12}"},"app":"$APP","space":"*",""" +
                    """"policy":"perm:$APP/g${p / 20}.p${p % 20}"}"""
            }
            return """{"schema":2,"revision":1,"publishedAt":"$PUBLISHED","roles":[$roles],"bindings":[$bindings]}"""
        }
    }
}
