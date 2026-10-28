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

package me.ahoo.cosec.engine.pattern

import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Resource
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class ActionResourcePatternTest {
    private fun target(type: String, id: String) = ResourceTarget(Resource(type, id, emptyMap()))

    private fun resource(pattern: String, type: String, id: String) =
        ResourcePattern.parse(pattern).match(target(type, id))?.toMap()

    @ParameterizedTest
    @CsvSource(
        "*, order, read, true",
        "order:*, order, read, true",
        "order:*, user, read, false",
        "order:read, order, read, true",
        "order:read, order, list, false",
        "order:read, Order, read, false",
        "http:GET, http, GET, true",
        "http:GET, http, get, false",
        "http:*, http, DELETE, true",
    )
    fun `actions match exactly`(pattern: String, service: String, verb: String, expected: Boolean) {
        ActionPattern.parse(pattern).matches(Action(service, verb)).assert().isEqualTo(expected)
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["", "order", ":read", "order:", "*:read", "ord*:read", "order:re*d", "order:read:x", "a b:c", "a:b c"]
    )
    fun `malformed actions do not compile`(pattern: String) {
        assertThrownBy<InvalidPatternException> { ActionPattern.parse(pattern) }
    }

    @Test
    fun `actions print and compare as written`() {
        listOf("*", "order:*", "order:read").forEach {
            ActionPattern.parse(it).toString().assert().isEqualTo(it)
            ActionPattern.parse(it).assert().isEqualTo(ActionPattern.parse(it))
            ActionPattern.parse(it).hashCode().assert().isEqualTo(ActionPattern.parse(it).hashCode())
        }
        ActionPattern.parse("*").assert().isSameAs(ActionPattern.ANY)
        ActionPattern.parse("order:*").service.assert().isEqualTo("order")
        ActionPattern.parse("order:*").verb.assert().isNull()
        ActionPattern.parse("order:read").assert().isNotEqualTo(ActionPattern.parse("order:list"))
        ActionPattern.parse("order:read").assert().isNotEqualTo("order:read")
    }

    @ParameterizedTest
    @CsvSource(
        "order, order, '', true",
        "order, order, 1, false",
        "order/{id}, order, 123, true",
        "order/{id}, order, '', false",
        "order/{id}, order, 1/2, false",
        "order/{id}, user, 123, false",
        "order/*, order, 123, true",
        "order/*, order, '', false",
        "order/*/items, order, 1/items, true",
        "order/*/items, order, 1, false",
        "order/*/items, order, /items, false",
        "order/*/items, order, 1/items/2, false",
        "order/1, order, 1, true",
        "order/1, order, 12, false",
        "order/**, order, '', true",
        "order/**, order, 1/2/3, true",
        "order/1/**, order, 1, true",
        "order/1/**, order, 1/2, true",
        "order/1/**, order, 12, false",
        "order/1/**, order, 2/1, false",
        "order/a%2Fb, order, a%2Fb, true",
        "order/a%2Fb, order, a/b, false",
    )
    fun `typed resources match segment by segment`(pattern: String, type: String, id: String, expected: Boolean) {
        (resource(pattern, type, id) != null).assert().isEqualTo(expected)
    }

    @Test
    fun `typed resources capture segments`() {
        resource("order/{orderId}/items/{itemId}", "order", "1/items/2")
            .assert().isEqualTo(mapOf("orderId" to "1", "itemId" to "2"))
        resource("order/{orderId}", "order", "%2A").assert().isEqualTo(mapOf("orderId" to "%2A"))
        ResourcePattern.parse("order/{a}/{b}").variableNames.assert().containsExactly("a", "b")
        ResourcePattern.parse("order/*").match(target("order", "1")).assert().isSameAs(Captures.NONE)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "/order", "order/", "order//1", "*", "ord*r", "order:x/1", "order/**/1", "order/a*", "order/{}",
            "order/{1a}", "order/{a b}", "order/{a}/{a}", "order/{a", "order/?", "http", "http/orders", "or der",
        ]
    )
    fun `malformed resources do not compile`(pattern: String) {
        assertThrownBy<InvalidPatternException> { ResourcePattern.parse(pattern) }
    }

    @Test
    fun `typed resources take no path options`() {
        assertThrownBy<InvalidPatternException> { ResourcePattern.parse("order/1", caseSensitive = false) }
        assertThrownBy<InvalidPatternException> { ResourcePattern.parse("order/1", decodeAndParseSegments = false) }
    }

    @Test
    fun `http resources match the path`() {
        val pattern = ResourcePattern.parse("http:path:/orders/{id}")
        pattern.match(target("http", "/orders/1")).assert().isNotNull()
        pattern.match(target("http", "/orders/1"))!!["id"].assert().isEqualTo("1")
        pattern.matches(target("http", "/users/1")).assert().isFalse()
        pattern.matches(target("order", "/orders/1")).assert().isFalse()
        pattern.variableNames.assert().containsExactly("id")
        pattern.toString().assert().isEqualTo("http:path:/orders/{id}")
        pattern.assert().isEqualTo(ResourcePattern.parse("http:path:/orders/{id}"))
        pattern.hashCode().assert().isEqualTo(ResourcePattern.parse("http:path:/orders/{id}").hashCode())
        pattern.assert().isNotEqualTo(ResourcePattern.parse("http:path:/orders/{id}", caseSensitive = false))
    }

    @Test
    fun `http resources take the path options`() {
        val target = target("http", "/Orders/a%20b")
        ResourcePattern.parse("http:path:/orders/*", caseSensitive = false).matches(target).assert().isTrue()
        ResourcePattern.parse("http:path:/Orders/a b").matches(target).assert().isTrue()
        ResourcePattern.parse("http:path:/Orders/a%20b", decodeAndParseSegments = false).matches(target)
            .assert().isTrue()
    }

    @Test
    fun `the target parses its path once per option`() {
        val target = target("http", "/a")
        target.httpPath(true).assert().isSameAs(target.httpPath(true))
        target.httpPath(false).assert().isSameAs(target.httpPath(false))
        target.httpPath(true).assert().isNotSameAs(target.httpPath(false))
    }

    @Test
    fun `a malformed http path throws rather than not matching`() {
        val deny = ResourcePattern.parse("http:path:/admin/**")
        assertThrownBy<IllegalArgumentException> { deny.matches(target("http", "/admin/%zz")) }
    }

    @Test
    fun `any resource matches everything and captures nothing`() {
        ResourcePattern.AnyResource.match(target("order", "1")).assert().isSameAs(Captures.NONE)
        ResourcePattern.AnyResource.variableNames.assert().isEmpty()
        ResourcePattern.AnyResource.toString().assert().isEqualTo("*")
    }

    @Test
    fun `typed patterns compare by text`() {
        val pattern = ResourcePattern.parse("order/{id}")
        pattern.assert().isEqualTo(ResourcePattern.parse("order/{id}"))
        pattern.hashCode().assert().isEqualTo(ResourcePattern.parse("order/{id}").hashCode())
        pattern.assert().isNotEqualTo(ResourcePattern.parse("order/*"))
        pattern.toString().assert().isEqualTo("order/{id}")
        (pattern as ResourcePattern.Typed).type.assert().isEqualTo("order")
    }

    @Test
    fun `invalid patterns say where`() {
        val error = assertThrownBy<InvalidPatternException> { ResourcePattern.parse("order/a*") }
        error.hasMessageContaining("[order/a*] at 7")
    }
}
