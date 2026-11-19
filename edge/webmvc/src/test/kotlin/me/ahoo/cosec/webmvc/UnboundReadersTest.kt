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

package me.ahoo.cosec.webmvc

import me.ahoo.cosec.webmvc.ServletRequests.requireSecurityContext
import me.ahoo.cosec.webmvc.ServletRequests.securityContextOrNull
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.MockHttpServletRequest

/**
 * UA-7: on Spring MVC, a reader's name says whether it throws or answers null, and a throwing reader says what to do
 * and names the stack's other reader.
 */
class UnboundReadersTest {
    @Test
    fun `the request's reader throws IllegalStateException naming the handler parameter and the holder`() {
        val request = MockHttpServletRequest()
        request.securityContextOrNull().assert().isNull()
        val message = assertThrows<IllegalStateException> { request.requireSecurityContext() }.message
        message.assert().isEqualTo(Unbound.ON_REQUEST)
            .contains("Register CoSec's filter").contains("handler parameter")
            .contains("SecurityContextHolder.requireSecurityContext()")
    }

    @Test
    fun `the holder throws IllegalStateException naming the request's reader`() {
        SecurityContextHolder.securityContextOrNull().assert().isNull()
        val message = assertThrows<IllegalStateException> { SecurityContextHolder.requireSecurityContext() }.message
        message.assert().isEqualTo(Unbound.ON_THREAD)
            .contains("handler parameter").contains("ServletRequests.requireSecurityContext(request)")
    }
}
