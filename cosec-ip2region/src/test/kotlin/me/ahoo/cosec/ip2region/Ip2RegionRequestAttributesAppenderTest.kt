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

package me.ahoo.cosec.ip2region

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.cosec.api.context.request.Request
import org.junit.jupiter.api.Test

class Ip2RegionRequestAttributesAppenderTest {
    private val ip2RegionRequestAttributesAppender = Ip2RegionRequestAttributesAppender()

    @Test
    fun append() {
        val request: Request = mockk {
            every { remoteIp } returns "101.228.87.88"
            every { mergeAttributes(mapOf(REQUEST_ATTRIBUTES_IP_REGION_KEY to "中国|0|上海|上海市|电信")) } returns mockk()
        }

        ip2RegionRequestAttributesAppender.append(request)

        verify {
            request.remoteIp
            request.mergeAttributes(mapOf(REQUEST_ATTRIBUTES_IP_REGION_KEY to "中国|0|上海|上海市|电信"))
        }
    }

    @Test
    fun appendWrongIp() {
        val request: Request = mockk {
            every { remoteIp } returns "localhost"
            every { mergeAttributes(mapOf(REQUEST_ATTRIBUTES_IP_REGION_KEY to "0|0|0|内网IP|内网IP")) } returns mockk()
        }
        ip2RegionRequestAttributesAppender.append(request)
        verify {
            request.remoteIp
            request.mergeAttributes(mapOf(REQUEST_ATTRIBUTES_IP_REGION_KEY to "0|0|0|内网IP|内网IP"))
        }
    }
}
