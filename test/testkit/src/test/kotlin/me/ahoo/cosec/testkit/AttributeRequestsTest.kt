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

@file:OptIn(ExperimentalCoSecApi::class)

package me.ahoo.cosec.testkit

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

class AttributeRequestsTest {
    private val action = Action("order", "read")
    private val resource = Resource("order", "7", emptyMap())
    private val reader = PipelineFixture.Principal(
        "alice",
        tenant = "acme",
        roles = setOf("reader", "picker@north"),
        app = "wms",
        attributes = mapOf("department" to "finance"),
    )

    @Test
    fun `the scope is resolved as the pipeline resolves it`() {
        val request = AttributeRequests.of(action, resource, reader, space = "north", hostApp = "wms")
        request.tenant.assert().isEqualTo(TenantId("acme"))
        request.subject.id.assert().isEqualTo("alice")
        request.subject.authenticated.assert().isTrue()
        request.subject.attributes.assert().isEqualTo(mapOf("department" to "finance"))
        val scope = checkNotNull(request.scope)
        scope.tenant.assert().isEqualTo(TenantId("acme"))
        scope.app.assert().isEqualTo(AppId("wms"))
        scope.space.assert().isEqualTo(SpaceId("north"))
        scope.roles.assert().containsExactly(RoleId("picker"))
        request.action.assert().isSameAs(action)
        request.resource.assert().isSameAs(resource)
    }

    @Test
    fun `a scope the pipeline rejects reaches no stage-B provider`() {
        assertThrownBy<IllegalArgumentException> {
            AttributeRequests.of(action, resource, reader, space = "south")
        }.message().contains("SPACE_NOT_MEMBER")
        assertThrownBy<IllegalArgumentException> {
            AttributeRequests.of(action, resource, reader, hostApp = "billing")
        }.message().contains("APP_MISMATCH")
    }

    @Test
    fun `an anonymous request is in the default tenant`() {
        val request = AttributeRequests.of(action, resource)
        request.tenant.assert().isEqualTo(TenantId.DEFAULT)
        request.subject.authenticated.assert().isFalse()
        checkNotNull(request.scope).roles.assert().isEmpty()
    }

    @Test
    fun `stage A has no scope yet`() {
        val request = AttributeRequests.beforeScope(action, resource, reader, remoteIp = "192.0.2.1", deviceId = "d1")
        request.scope.assert().isNull()
        request.tenant.assert().isEqualTo(TenantId("acme"))
        request.remoteIp.assert().isEqualTo("192.0.2.1")
        request.deviceId.assert().isEqualTo("d1")
    }
}
