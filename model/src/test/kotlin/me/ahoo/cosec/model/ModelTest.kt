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

package me.ahoo.cosec.model

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class ModelTest {
    @Test
    fun `a request keeps what it is given`() {
        val subject = Subject("user", "tenant", true, setOf("admin@space"), setOf("p"), mapOf("k" to 1), root = false)
        val scope = Scope.declared("tenant", "app", "space")
        val action = Action("http", "GET")
        val resource = Resource("http", "/orders/1", mapOf("resource.owner" to "user"))
        val environment = Environment("127.0.0.1", "request", "device", blocked = false)
        val request = AuthzRequest(subject, scope, action, resource, environment)

        request.subject.assert().isSameAs(subject)
        request.scope.assert().isSameAs(scope)
        request.action.assert().isSameAs(action)
        request.resource.assert().isSameAs(resource)
        request.environment.assert().isSameAs(environment)
        listOf(
            subject.id,
            subject.tenantId,
            subject.authenticated,
            subject.roles,
            subject.policies,
            subject.attributes,
            subject.root
        )
            .assert().containsExactly("user", "tenant", true, setOf("admin@space"), setOf("p"), mapOf("k" to 1), false)
        listOf(action.namespace, action.verb, resource.type, resource.id, resource.attributes)
            .assert().containsExactly("http", "GET", "http", "/orders/1", mapOf("resource.owner" to "user"))
        listOf(environment.remoteIp, environment.requestId, environment.deviceId, environment.blocked)
            .assert().containsExactly("127.0.0.1", "request", "device", false)
    }

    /** Until a ScopeResolver exists, every scope comes from the client and says so. */
    @Test
    fun `a declared scope is marked as declared`() {
        val scope = Scope.declared("tenant", "app", "space")
        listOf(scope.tenantId, scope.appId, scope.spaceId).assert().containsExactly("tenant", "app", "space")
        scope.source.assert().isEqualTo(Scope.Source.DECLARED)
    }
}
