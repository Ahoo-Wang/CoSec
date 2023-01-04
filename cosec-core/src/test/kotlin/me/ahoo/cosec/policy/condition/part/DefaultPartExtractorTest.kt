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
import me.ahoo.cosec.api.context.request.Request
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class DefaultPartExtractorTest {

    @Test
    fun extractRequestPath() {
        val request: Request = mockk {
            every { path } returns "path"
        }
        assertThat(DefaultPartExtractor(RequestParts.PATH).extract(request, mockk()), equalTo("path"))
    }

    @Test
    fun extractRequestMethod() {
        val request: Request = mockk {
            every { method } returns "method"
        }
        assertThat(DefaultPartExtractor(RequestParts.METHOD).extract(request, mockk()), equalTo("method"))
    }

    @Test
    fun extractRequestRemoteIp() {
        val request: Request = mockk {
            every { remoteIp } returns "remoteIp"
        }
        assertThat(DefaultPartExtractor(RequestParts.REMOTE_IP).extract(request, mockk()), equalTo("remoteIp"))
    }

    @Test
    fun extractRequestOrigin() {
        val request: Request = mockk {
            every { origin } returns "origin"
        }
        assertThat(DefaultPartExtractor(RequestParts.ORIGIN).extract(request, mockk()), equalTo("origin"))
    }

    @Test
    fun extractRequestReferer() {
        val request: Request = mockk {
            every { referer } returns "referer"
        }
        assertThat(DefaultPartExtractor(RequestParts.REFERER).extract(request, mockk()), equalTo("referer"))
    }

    @Test
    fun extractRequestHeader() {
        val request: Request = mockk {
            every { getHeader("key") } returns "value"
        }
        assertThat(
            DefaultPartExtractor(RequestParts.HEADER_PREFIX + "key").extract(request, mockk()),
            equalTo("value")
        )
    }

    @Test
    fun extractContextTenantId() {
        val context: SecurityContext = mockk {
            every { tenant.tenantId } returns "tenantId"
        }
        assertThat(DefaultPartExtractor(SecurityContextParts.TENANT_ID).extract(mockk(), context), equalTo("tenantId"))
    }

    @Test
    fun extractContextPrincipalId() {
        val context: SecurityContext = mockk {
            every { principal.id } returns "principal.id"
        }
        assertThat(
            DefaultPartExtractor(SecurityContextParts.PRINCIPAL_ID).extract(mockk(), context),
            equalTo("principal.id")
        )
    }

    @Test
    fun extractContextPrincipalName() {
        val context: SecurityContext = mockk {
            every { principal.name } returns "principal.name"
        }
        assertThat(
            DefaultPartExtractor(SecurityContextParts.PRINCIPAL_NAME).extract(mockk(), context),
            equalTo("principal.name")
        )
    }

    @Test
    fun extractWhenWrongPart() {
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            DefaultPartExtractor("wrongPart").extract(mockk(), mockk())
        }
    }
}
