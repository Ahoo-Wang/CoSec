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

package me.ahoo.cosec.runtime.identity

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.identity.IdentityCodec
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.token.TokenSession
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import java.time.Instant

/**
 * The `CoSec-Identity` header a gateway writes for every permitted request to a route that receives it
 * (`IdentityIssuer.issue` → [IdentityCodec.encode]), and what the service behind reads ([IdentityCodec.decode]).
 * `minimal` is an API key with one role; `typical` CoSec's token with its session, three roles and two claims; `large`
 * twenty roles and eight claims, some outside ASCII. `decode` is unchanged by the writer's checks: the run's control.
 */
@State(org.openjdk.jmh.annotations.Scope.Benchmark)
open class IdentityCodecBenchmark {
    @Param("minimal", "typical", "large")
    var identity: String = ""

    private lateinit var input: Identity
    private lateinit var header: String

    @Setup
    fun setup() {
        val now = Instant.parse("2026-11-01T00:00:00Z")
        input = when (identity) {
            "minimal" -> Identity(
                tenant = "acme",
                subject = "svc-reporting",
                app = "order",
                space = "",
                roles = setOf("reader"),
                credential = Identity.Credential(Identity.Method.API_KEY, Identity.Credential.Kind.WORKLOAD, "k-7f3a", now.plusSeconds(86_400)),
                issuedAt = now,
                requestId = "0f8e2c1d9b7a4e65",
            )
            "typical" -> Identity(
                tenant = "acme",
                subject = "alice",
                app = "order",
                space = "team-a",
                roles = setOf("clerk", "reviewer", "approver"),
                credential = Identity.Credential(
                    Identity.Method.JWT,
                    Identity.Credential.Kind.PERSON,
                    "9c1d7e3a-5b2f-4a8e-b6d0-3f1e2a9c4b7d",
                    now.plusSeconds(3_600),
                    issuedAt = now.minusSeconds(60),
                    session = TokenSession("s-41c2", now.minusSeconds(7_200), 3),
                ),
                issuedAt = now,
                requestId = "0f8e2c1d9b7a4e65",
                claims = mapOf("name" to "Alice", "email" to "alice@example.com"),
            )
            "large" -> Identity(
                tenant = "acme",
                subject = "bob",
                app = "order",
                space = "team-b",
                roles = List(20) { "role-$it" }.toSet(),
                credential = Identity.Credential(Identity.Method.JWT, Identity.Credential.Kind.PERSON, "jti-large", now.plusSeconds(3_600)),
                issuedAt = now,
                requestId = "0f8e2c1d9b7a4e65",
                claims = List(8) { "claim$it" to "值-$it-ünïcødé" }.toMap(),
            )
            else -> error("Unknown identity: $identity")
        }
        header = IdentityCodec.encode(input)
        check(IdentityCodec.decode(header) == input) { "The header does not read back: $header" }
    }

    @Benchmark
    fun encode(): String = IdentityCodec.encode(input)

    @Benchmark
    fun decode(): Identity = IdentityCodec.decode(header)
}
