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

package me.ahoo.cosec.system

import me.ahoo.test.asserts.assert
import java.net.URI

/**
 * The operator's routes of the system's own applications (`test/system-auth`, `test/system-enforcer`), which the platform
 * document permits to the platform administrator `root` only and no gateway routes to.
 */
object Operator {
    /** An API key issued into the shared Redis store: the key (shown once) and its hash, which revokes it. */
    class IssuedKey(val key: String, val hash: String)

    private val root get() = SignIn.of(SignIn.ROOT, "(platform)", app = "orders")

    /** Revokes every token [principal] of [tenant] holds (`TokenRevoker.revokeSubject`, MEM-4). */
    fun revokeSubject(tenant: String, principal: String) {
        val answer = Http.post(
            URI.create("${SystemTopology.auth}/admin/revoke-subject"),
            mapOf("tenant" to tenant, "principal" to principal),
            SignIn.root().bearer,
        )
        answer.status.assert().describedAs("revoking $principal of $tenant: $answer").isEqualTo(200)
    }

    fun issueKey(id: String, principal: String, tenant: String, roles: List<String>?, expiresAt: String): IssuedKey {
        val body = mapOf(
            "id" to id,
            "principal" to principal,
            "tenant" to tenant,
            "app" to "orders",
            "roles" to roles,
            "expiresAt" to expiresAt
        )
        val answer = Http.post(URI.create("${SystemTopology.enforcer}/admin/api-keys"), body, root.bearer)
        answer.status.assert().describedAs("issuing key $id: $answer").isEqualTo(200)
        return IssuedKey(answer.json().getValue("key") as String, answer.json().getValue("hash") as String)
    }

    fun revokeKey(hash: String) {
        val answer = Http.send(
            "DELETE",
            URI.create("${SystemTopology.enforcer}/admin/api-keys/$hash"),
            listOf(root.bearer),
            null
        )
        answer.status.assert().describedAs("revoking a key: $answer").isEqualTo(200)
    }
}
