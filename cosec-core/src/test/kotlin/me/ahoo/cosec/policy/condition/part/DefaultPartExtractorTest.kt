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

package me.ahoo.cosec.policy.condition.part

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.AppIdCapable
import me.ahoo.cosec.api.context.request.DeviceIdCapable
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.action.getPathVariables
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import java.net.URI

class DefaultPartExtractorTest {

    @Test
    fun extractRequestPath() {
        val request: Request = EvaluateRequest(path = "path")
        DefaultPartExtractor(RequestParts.PATH).extract(request, mockk()).assert().isEqualTo("path")
    }

    @Test
    fun extractRequestMethod() {
        val request: Request = EvaluateRequest(method = "method")

        DefaultPartExtractor(RequestParts.METHOD).extract(request, mockk()).assert().isEqualTo("method")
    }

    @Test
    fun extractRequestRemoteIp() {
        val request: Request = EvaluateRequest(remoteIp = "remoteIp")
        DefaultPartExtractor(RequestParts.REMOTE_IP).extract(request, mockk()).assert().isEqualTo("remoteIp")
    }

    @Test
    fun extractRequestAppId() {
        val request: Request = EvaluateRequest(
            headers = mapOf(
                AppIdCapable.APP_ID_KEY to "appId"
            )
        )
        DefaultPartExtractor(RequestParts.APP_ID).extract(request, mockk()).assert().isEqualTo("appId")
    }

    @Test
    fun extractRequestDeviceId() {
        val request: Request = EvaluateRequest(
            headers = mapOf(
                DeviceIdCapable.DEVICE_ID_KEY to "deviceId"
            )
        )

        DefaultPartExtractor(RequestParts.DEVICE_ID).extract(request, mockk()).assert().isEqualTo("deviceId")
    }

    @Test
    fun extractRequestOrigin() {
        val request: Request = EvaluateRequest(origin = URI.create("origin"))
        DefaultPartExtractor(RequestParts.ORIGIN).extract(request, mockk()).assert().isEqualTo("origin")
    }

    @Test
    fun extractRequestReferer() {
        val request: Request = EvaluateRequest(referer = URI.create("referer"))
        DefaultPartExtractor(RequestParts.REFERER).extract(request, mockk()).assert().isEqualTo("referer")
    }

    @Test
    fun extractRequestPathVar() {
        val context: SecurityContext = mockk {
            every { getPathVariables() } returns mapOf("id" to "id")
        }

        DefaultPartExtractor(RequestParts.PATH_VAR_PREFIX + "id").extract(mockk(), context).assert().isEqualTo("id")
    }

    @Test
    fun extractRequestHeader() {
        val request: Request = mockk {
            every { getHeader("key") } returns "value"
        }

        DefaultPartExtractor(RequestParts.HEADER_PREFIX + "key").extract(request, mockk()).assert().isEqualTo("value")
    }

    @Test
    fun extractContextTenantId() {
        val context: SecurityContext = mockk {
            every { tenant.tenantId } returns "tenantId"
        }
        DefaultPartExtractor(SecurityContextParts.TENANT_ID).extract(mockk(), context).assert().isEqualTo("tenantId")
    }

    @Test
    fun extractContextPrincipalId() {
        val context: SecurityContext = mockk {
            every { principal.id } returns "principal.id"
        }

        DefaultPartExtractor(SecurityContextParts.PRINCIPAL_ID).extract(mockk(), context).assert()
            .isEqualTo("principal.id")
    }

    @Test
    fun extractRequestAttributes() {
        val request: Request = mockk {
            every { attributes["key"] } returns "value"
            every { attributes["not_exist"] } returns null
        }

        DefaultPartExtractor(RequestParts.ATTRIBUTES_PREFIX + "key").extract(request, mockk()).assert().isEqualTo(
            "value"
        )

        DefaultPartExtractor(RequestParts.ATTRIBUTES_PREFIX + "not_exist").extract(request, mockk()).assert()
            .isEqualTo("")
    }

    @Test
    fun extractContextPrincipalAttributes() {
        val context: SecurityContext = mockk {
            every { principal.attributes["key"] } returns "value"
            every { principal.attributes["not_exist"] } returns null
        }

        DefaultPartExtractor(SecurityContextParts.PRINCIPAL_ATTRIBUTES_PREFIX + "key").extract(mockk(), context)
            .assert().isEqualTo("value")

        DefaultPartExtractor(SecurityContextParts.PRINCIPAL_ATTRIBUTES_PREFIX + "not_exist").extract(
            mockk(),
            context,
        ).assert().isEqualTo("")
    }

    @Test
    fun extractWhenWrongPart() {
        assertThrownBy<IllegalArgumentException> {
            DefaultPartExtractor("wrongPart").extract(mockk(), mockk())
        }
    }
}
