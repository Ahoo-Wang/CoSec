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

package me.ahoo.cosec.explain

import me.ahoo.cosec.api.principal.TenantPrincipal
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class ExplainInputTest {
    @Test
    fun principal() {
        val principal = ExplainPrincipal(id = "user", tenantId = "t1", roles = setOf("r"), policies = setOf("p"))
            .toPrincipal()
        principal.id.assert().isEqualTo("user")
        principal.roles.assert().containsExactly("r")
        principal.policies.assert().containsExactly("p")
        (principal as TenantPrincipal).tenant.tenantId.assert().isEqualTo("t1")
        (ExplainPrincipal(id = "user").toPrincipal() is TenantPrincipal).assert().isFalse()
    }

    @Test
    fun request() {
        val request = ExplainRequest(
            path = "/orders",
            method = "POST",
            remoteIp = "10.0.0.1",
            appId = "app",
            spaceId = "space",
            deviceId = "device",
            origin = "https://example.com",
            headers = mapOf("X-Tier" to "gold"),
            query = mapOf("q" to "1"),
            cookies = mapOf("c" to "2"),
            attributes = mapOf("ipRegion" to "cn"),
        ).toRequest()
        request.path.assert().isEqualTo("/orders")
        request.method.assert().isEqualTo("POST")
        request.remoteIp.assert().isEqualTo("10.0.0.1")
        request.appId.assert().isEqualTo("app")
        request.spaceId.assert().isEqualTo("space")
        request.deviceId.assert().isEqualTo("device")
        request.origin.host.assert().isEqualTo("example.com")
        request.getHeader("X-Tier").assert().isEqualTo("gold")
        request.getQuery("q").assert().isEqualTo("1")
        request.getCookieValue("c").assert().isEqualTo("2")
        request.attributes.assert().containsEntry("ipRegion", "cn")
        ExplainRequest(path = "/").toRequest().appId.assert().isEmpty()
    }
}
