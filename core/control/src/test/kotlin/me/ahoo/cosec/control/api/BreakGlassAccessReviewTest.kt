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

package me.ahoo.cosec.control.api

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.engine.BuiltinPolicies
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * A break-glass binding is in an access review as what it is: an allow of `cosec:root`, break-glass, conditional on its
 * window, with that window. It is a platform principal's, so it is in the platform tenant's review (#1206).
 */
class BreakGlassAccessReviewTest {
    private val fixture = ControlPlaneTest()
    private val http = ControlHttp(fixture.plane)
    private val plato = fixture.engine.security(TenantId.PLATFORM, "plato")

    @Test
    fun `an access review lists a break-glass binding with its window`() {
        runBlocking {
            val head = fixture.plane.read(fixture.plato, SnapshotKey.Platform)
            val glass = Binding(
                BindingId("glass"),
                SubjectRef.Principal(TenantId.PLATFORM, PrincipalId("rita")),
                BindingScope(Match.Any, Match.Any),
                BuiltinPolicies.ROOT,
                Instant.parse("2026-11-01T01:00:00Z").toEpochMilli(),
                breakGlass = true,
            )
            fixture.bootstrap(SnapshotKey.Platform, head.policies, head.bindings + glass)
        }
        val query = """{"tenant":"(platform)","action":"order:read","resource":"order/1"}"""
        val response = runBlocking {
            http.handle(
                ControlHttpRequest("POST", "/analyze", emptyMap(), """{"query":$query}""".encodeToByteArray()),
                plato
            )
        }
        response.status.assert().isEqualTo(200)
        val report = CoSecJson.mapper.readTree(response.body)
        report.get("opaque").asInt().assert().isEqualTo(0)
        val glass = report.get("allows").single { it.get("binding").asString() == "glass" }
        glass.get("policy").asString().assert().isEqualTo("cosec:root")
        glass.get("breakGlass").asBoolean().assert().isTrue()
        glass.get("conditional").asBoolean().assert().isTrue()
        glass.get("expiresAt").asString().assert().isEqualTo("2026-11-01T01:00:00Z")
    }
}
