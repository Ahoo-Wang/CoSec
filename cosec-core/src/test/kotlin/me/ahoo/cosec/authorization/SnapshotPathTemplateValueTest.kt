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

package me.ahoo.cosec.authorization

import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.authorization.engine.PolicySnapshotCompiler
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.parity.LegacyData
import me.ahoo.cosec.parity.policyJson
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.snapshot.ManualClock
import me.ahoo.cosec.snapshot.SnapshotOptions
import me.ahoo.cosec.snapshot.SnapshotSource
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration

/** The snapshot compiler reads statements with the same deserializers, so rejected template values decide alike. */
class SnapshotPathTemplateValueTest {
    @Test
    fun rejectedValuesDecideAsInTheRepositories() {
        val store = InMemorySnapshotDocumentStore()
        LegacyData(
            policies = linkedMapOf(
                "g" to policyJson(
                    "g",
                    "global",
                    """{"name":"own","action":"/users/#{principal.id}/**"},""" +
                        """{"name":"blocked","effect":"deny","action":"/api/blocked/#{principal.id}/**"}""",
                ),
            ),
            globalIndex = listOf("g"),
        ).publish(store)
        val compiler = PolicySnapshotCompiler()
        val options = SnapshotOptions(revalidateInterval = Duration.ZERO)
        SnapshotSource(store, compiler.policies, compiler.app, options, ManualClock()).use { source ->
            val authorization = SnapshotEngineAuthorization(source)
            fun decide(path: String, id: String) = authorization
                .decide(EvaluateRequest(path = path, method = "GET"), SimpleSecurityContext(SimplePrincipal(id)))
                .block()!!.result
            PathTemplateValueTest.REJECTED.forEach { id ->
                decide("/users/victim/orders", id).assert().describedAs("id [$id]")
                    .isEqualTo(AuthorizeResult.EXPLICIT_DENY)
            }
            decide("/users/alice/orders", "alice").assert().isEqualTo(AuthorizeResult.ALLOW)
            decide("/users/victim/orders", "alice").assert().isEqualTo(AuthorizeResult.IMPLICIT_DENY)
        }
    }
}
