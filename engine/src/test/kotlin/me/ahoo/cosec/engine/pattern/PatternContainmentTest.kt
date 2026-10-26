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

import me.ahoo.cosec.model.condition.Containment
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/** RFC 0005 §5.5 case by case; [PatternContainmentPropertyTest] checks soundness (A7). */
class PatternContainmentTest {
    private fun resource(text: String, caseSensitive: Boolean = true, decode: Boolean = true) =
        ResourcePattern.parse(text, caseSensitive, decode)

    private fun contains(outer: String, inner: String): ResourceContainment =
        PatternContainment.contains(resource(outer), resource(inner))

    @ParameterizedTest
    @CsvSource(
        "*, order:read, YES",
        "*, *, YES",
        "order:*, order:read, YES",
        "order:*, order:*, YES",
        "order:read, order:read, YES",
        "order:read, order:*, UNKNOWN",
        "order:*, *, UNKNOWN",
        "order:*, user:read, UNKNOWN",
        "order:read, order:list, UNKNOWN",
        "order:read, Order:read, UNKNOWN",
    )
    fun `action containment`(outer: String, inner: String, expected: Containment) {
        PatternContainment.contains(ActionPattern.parse(outer), ActionPattern.parse(inner)).assert().isEqualTo(expected)
    }

    @ParameterizedTest
    @CsvSource(
        "order:read, order:list, true",
        "order:*, user:read, true",
        "order:*, order:read, false",
        "*, order:read, false",
        "order:read, order:read, false",
    )
    fun `action disjointness`(a: String, b: String, expected: Boolean) {
        PatternContainment.disjoint(ActionPattern.parse(a), ActionPattern.parse(b)).assert().isEqualTo(expected)
        PatternContainment.disjoint(ActionPattern.parse(b), ActionPattern.parse(a)).assert().isEqualTo(expected)
    }

    @ParameterizedTest
    @CsvSource(
        "order/**, order/a/b, YES",
        "order/**, order, YES",
        "order/**, order/**, YES",
        "order/*, order/a, YES",
        "order/*, order/{id}, YES",
        "order/{id}, order/*, YES",
        "order/{id}/**, order/a/b/**, YES",
        "order/a, order/a, YES",
        "order/a, order/A, UNKNOWN",
        "order/a, order/*, UNKNOWN",
        "order/*, order/a/b, UNKNOWN",
        "order/*, order/**, UNKNOWN",
        "order/a/**, order/b/c, UNKNOWN",
        "order, order/a, UNKNOWN",
        "order/**, user/a, UNKNOWN",
        "order/**, http:path:/order, UNKNOWN",
    )
    fun `typed resource containment`(outer: String, inner: String, expected: Containment) {
        contains(outer, inner).answer.assert().isEqualTo(expected)
    }

    @ParameterizedTest
    @CsvSource(
        "http:path:/orders/**, http:path:/orders/1/items, YES",
        "http:path:/orders/**, http:path:/orders, YES",
        "http:path:/orders/**, http:path:/orders/, YES",
        "http:path:/orders/**, http:path:/orders/{*rest}, YES",
        "http:path:/**, http:path:/a/b, YES",
        "http:path:/orders/*, http:path:/orders/{id}, YES",
        "http:path:/orders/{id}, http:path:/orders/1, YES",
        "http:path:/orders/*, http:path:/orders/*, YES",
        "http:path:/orders/a?c, http:path:/orders/a?c, YES",
        "http:path:/orders/{id}/x, http:path:/orders/a*b/x, YES",
        "http:path:/orders/{id}, http:path:/orders/*, UNKNOWN",
        "http:path:/orders/{id}/**, http:path:/orders/*, UNKNOWN",
        "http:path:/orders/{id}/x, http:path:/orders/*/x, YES",
        "http:path:/orders/a?c, http:path:/orders/abc, UNKNOWN",
        "http:path:/orders/a*, http:path:/orders/ab, UNKNOWN",
        "http:path:/orders/**, http:path:/ordersX, UNKNOWN",
        "http:path:/orders/**, http:path:/orders**, UNKNOWN",
        "http:path:/**, http:path:**/x, UNKNOWN",
        "http:path:/**/x, http:path:/a/x, UNKNOWN",
        "http:path:/**/x, http:path:/**/x, YES",
        "http:path:/a, http:path:a, UNKNOWN",
        "http:path:/orders, http:path:/orders/, UNKNOWN",
    )
    fun `http path containment`(outer: String, inner: String, expected: Containment) {
        contains(outer, inner).answer.assert().isEqualTo(expected)
    }

    @Test
    fun `different path options are contained only when nothing differs`() {
        val sensitive = resource("http:path:/orders/**")
        val insensitive = resource("http:path:/orders/**", caseSensitive = false)
        val raw = resource("http:path:/orders/**", decode = false)
        PatternContainment.contains(sensitive, insensitive).answer.assert().isEqualTo(Containment.UNKNOWN)
        PatternContainment.contains(insensitive, sensitive).answer.assert().isEqualTo(Containment.UNKNOWN)
        PatternContainment.contains(sensitive, raw).answer.assert().isEqualTo(Containment.UNKNOWN)
        PatternContainment.contains(insensitive, resource("http:path:/orders/1", caseSensitive = false)).answer
            .assert().isEqualTo(Containment.YES)
    }

    @Test
    fun `any resource contains everything and only itself contains it`() {
        PatternContainment.contains(ResourcePattern.AnyResource, resource("order/a")).answer.assert()
            .isEqualTo(Containment.YES)
        PatternContainment.contains(resource("order/**"), ResourcePattern.AnyResource).answer.assert()
            .isEqualTo(Containment.UNKNOWN)
        PatternContainment.disjoint(ResourcePattern.AnyResource, resource("order/a")).assert().isFalse()
    }

    @Test
    fun `variables map to the inner pattern's variable at the same position`() {
        val typed = contains("order/{id}/{line}", "order/{orderId}/x") as ResourceContainment.Contained
        typed.variables.assert().isEqualTo(mapOf("id" to "orderId"))
        val http = contains("http:path:/orders/{id}/{*rest}", "http:path:/orders/{o}/{*tail}")
            as ResourceContainment.Contained
        http.variables.assert().isEqualTo(mapOf("id" to "o", "rest" to "tail"))
        val same = contains("http:path:/a/{x}{y}z", "http:path:/a/{x}{y}z") as ResourceContainment.Contained
        same.variables.assert().isEqualTo(mapOf("x" to "x", "y" to "y"))
        val rest = contains("http:path:/orders/{*rest}", "http:path:/orders/a") as ResourceContainment.Contained
        rest.variables.assert().isEmpty()
    }

    @ParameterizedTest
    @CsvSource(
        "order/a, user/a, true",
        "order/a/**, order/b, true",
        "order/{id}/a, order/*/b, true",
        "order/a, order/*, false",
        "order/a/**, order/a/b, false",
        "order/**, http:path:/order, true",
        "http:path:/a/b, http:path:/a/c, true",
        "http:path:/a/**, http:path:/b, true",
        "http:path:/a/*, http:path:/a/b, false",
        "http:path:/**/a, http:path:/b, false",
        "http:path:/{*r}, http:path:/b, false",
    )
    fun `resource disjointness`(a: String, b: String, expected: Boolean) {
        PatternContainment.disjoint(resource(a), resource(b)).assert().isEqualTo(expected)
        PatternContainment.disjoint(resource(b), resource(a)).assert().isEqualTo(expected)
    }

    @Test
    fun `case-insensitive or differently decoded paths are never disjoint by their literals`() {
        PatternContainment.disjoint(
            resource("http:path:/a", caseSensitive = false),
            resource("http:path:/b", caseSensitive = false)
        ).assert().isFalse()
        PatternContainment.disjoint(
            resource("http:path:/a"),
            resource("http:path:/b", decode = false)
        ).assert().isFalse()
    }
}
