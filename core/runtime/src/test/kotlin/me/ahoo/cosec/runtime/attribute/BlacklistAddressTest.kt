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

package me.ahoo.cosec.runtime.attribute

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.Subject
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.attribute.AttributeRequest
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** R1A-4: the blacklist compares an address in one spelling, whichever the request and the operator wrote. */
class BlacklistAddressTest {
    private fun request(remoteIp: String) = AttributeRequest(
        TenantId.DEFAULT,
        Subject("(anonymous)", authenticated = false, emptyMap(), principalBindings = false, breakGlass = false),
        null,
        Action("http", "GET"),
        Resource("http", "/", emptyMap()),
        remoteIp,
        "r1",
        "",
    )

    private fun blocked(provider: BlacklistAttributeProvider, remoteIp: String): Any? = runBlocking {
        provider.fetch(request(remoteIp), setOf(BlacklistAttributeProvider.IP))[BlacklistAttributeProvider.IP]
    }

    @Test
    fun `every spelling of a blocked address is blocked`() {
        val source = InMemoryBlacklistSource().apply {
            block(BlacklistKind.IP, "2001:DB8:0:0:0:0:0:1")
            block(BlacklistKind.IP, "::ffff:192.0.2.1")
        }
        val provider = BlacklistAttributeProvider(source)
        listOf("2001:DB8::1", "2001:db8:0:0:0:0:0:1", "2001:db8::1", "192.0.2.1", "::FFFF:192.0.2.1").forEach {
            blocked(provider, it).assert().isEqualTo(true)
        }
        blocked(provider, "2001:db8::2").assert().isEqualTo(false)
        source.unblock(BlacklistKind.IP, "2001:db8::1")
        blocked(provider, "2001:DB8::1").assert().isEqualTo(false)
    }

    @Test
    fun `a source is asked in the canonical form`() {
        val asked = ArrayList<String>()
        val provider = BlacklistAttributeProvider { _, value ->
            asked += value
            false
        }
        listOf("2001:DB8:0:0:0:0:0:1", "::ffff:192.0.2.1", "10.0.0.1").forEach { blocked(provider, it) }
        asked.assert().containsExactly("2001:db8::1", "192.0.2.1", "10.0.0.1")
    }

    @Test
    fun `no address is unavailable, and an operator's typo is refused`() {
        val provider = BlacklistAttributeProvider(InMemoryBlacklistSource())
        assertThrows<IllegalStateException> { blocked(provider, "unknown") }
        assertThrows<IllegalArgumentException> { InMemoryBlacklistSource().block(BlacklistKind.IP, "10.0.0.256") }
        InMemoryBlacklistSource().block(BlacklistKind.DEVICE, "not an address")
        // No client address at all: nothing is looked up, and the attribute is absent.
        blocked(provider, "").assert().isNull()
    }
}
