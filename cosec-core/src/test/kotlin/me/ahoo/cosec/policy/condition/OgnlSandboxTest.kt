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

import me.ahoo.cosec.Delegated
import me.ahoo.cosec.api.context.SecurityContext
import ognl.Ognl
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class OgnlSandboxTest {
    private val context = Ognl.createDefaultContext("root")

    private fun accessible(member: java.lang.reflect.Member): Boolean =
        SecureOgnlMemberAccess.isAccessible(context, null, member, null)

    @Test
    fun `should deny non-public member`() {
        val nonPublicField = String::class.java.getDeclaredField("hash")
        assertThat(accessible(nonPublicField), `is`(false))
    }

    @Test
    fun `should deny field access`() {
        val publicField = Math::class.java.getField("PI")
        assertThat(accessible(publicField), `is`(false))
    }

    @Test
    fun `should deny getClass reflection entry point`() {
        assertThat(accessible(Any::class.java.getMethod("getClass")), `is`(false))
    }

    @Test
    fun `should deny getDelegate adapter escape`() {
        assertThat(accessible(DelegatedHolder::class.java.getMethod("getDelegate")), `is`(false))
    }

    @Test
    fun `should deny setters`() {
        val setter = SecurityContext::class.java
            .getMethod("setAttributeValue", String::class.java, Any::class.java)
        assertThat(accessible(setter), `is`(false))
    }

    @Test
    fun `should deny mutating collection methods`() {
        val put = java.util.Hashtable::class.java.getMethod("put", Any::class.java, Any::class.java)
        assertThat(accessible(put), `is`(false))
    }

    @Test
    fun `should deny member of a denylisted class`() {
        assertThat(accessible(System::class.java.getMethod("getProperties")), `is`(false))
    }

    @Test
    fun `should deny member of a subclass of a denylisted type`() {
        // URLClassLoader is a subclass of ClassLoader; getURLs is declared on the subclass.
        val getUrls = java.net.URLClassLoader::class.java.getMethod("getURLs")
        assertThat(accessible(getUrls), `is`(false))
    }

    @Test
    fun `should deny member of a denylisted package`() {
        assertThat(accessible(java.io.File::class.java.getMethod("getPath")), `is`(false))
    }

    @Test
    fun `should allow read-only getter of a safe type`() {
        assertThat(accessible(String::class.java.getMethod("length")), `is`(true))
    }

    @Test
    fun `should allow read-only method with arguments of a safe type`() {
        val startsWith = String::class.java.getMethod("startsWith", String::class.java)
        assertThat(accessible(startsWith), `is`(true))
    }

    @Test
    fun `should allow URI getters used by origin and referer conditions`() {
        assertThat(accessible(java.net.URI::class.java.getMethod("getHost")), `is`(true))
        assertThat(accessible(java.net.URI::class.java.getMethod("getScheme")), `is`(true))
    }

    @Test
    fun `should deny all class resolution`() {
        assertThrows<ClassNotFoundException> {
            DenyAllOgnlClassResolver.classForName<Any>("java.lang.Runtime", context)
        }
    }

    private class DelegatedHolder(override val delegate: String) : Delegated<String>
}
