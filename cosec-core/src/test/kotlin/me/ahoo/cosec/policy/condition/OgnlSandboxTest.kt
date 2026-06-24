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

package me.ahoo.cosec.policy.condition

import ognl.Ognl
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class OgnlSandboxTest {
    private val context = Ognl.createDefaultContext("root")

    @Test
    fun `should deny non-public member`() {
        val nonPublicField = Integer::class.java.getDeclaredField("value")
        assertThat(SecureOgnlMemberAccess.isAccessible(context, 1, nonPublicField, "value"), `is`(false))
    }

    @Test
    fun `should deny getClass reflection entry point`() {
        val getClass = Any::class.java.getMethod("getClass")
        assertThat(SecureOgnlMemberAccess.isAccessible(context, Any(), getClass, null), `is`(false))
    }

    @Test
    fun `should deny member of a denylisted class`() {
        val getProperties = System::class.java.getMethod("getProperties")
        assertThat(SecureOgnlMemberAccess.isAccessible(context, null, getProperties, null), `is`(false))
    }

    @Test
    fun `should deny member of a denylisted package`() {
        val getPath = java.io.File::class.java.getMethod("getPath")
        assertThat(SecureOgnlMemberAccess.isAccessible(context, java.io.File("x"), getPath, "path"), `is`(false))
    }

    @Test
    fun `should allow public member of a safe type`() {
        val length = String::class.java.getMethod("length")
        assertThat(SecureOgnlMemberAccess.isAccessible(context, "x", length, null), `is`(true))
    }

    @Test
    fun `should deny all class resolution`() {
        assertThrows<ClassNotFoundException> {
            DenyAllOgnlClassResolver.classForName<Any>("java.lang.Runtime", context)
        }
    }
}
