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

package me.ahoo.cosec.webmvc

import io.mockk.mockk
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

internal class SecurityContextHolderTest {
    @Test
    fun test() {
        CoSecSecurityContextHolder.securityContextOrNull().assert().isNull()
        val context = PermittingPipeline.context()
        CoSecSecurityContextHolder.setContext(context)
        CoSecSecurityContextHolder.securityContextOrNull().assert().isSameAs(context)
        CoSecSecurityContextHolder.remove()
        assertThrownBy<IllegalStateException> {
            CoSecSecurityContextHolder.requireSecurityContext()
        }
    }

    /** `PIP-27`: a context the pipeline did not make reads as none, so it is never taken for a caller. */
    @Test
    fun `a context the pipeline did not make reads as none`() {
        try {
            CoSecSecurityContextHolder.setContext(mockk<SecurityContext>())
            CoSecSecurityContextHolder.securityContextOrNull().assert().isNull()
            assertThrownBy<IllegalStateException> { CoSecSecurityContextHolder.requireSecurityContext() }
        } finally {
            CoSecSecurityContextHolder.remove()
        }
    }
}
