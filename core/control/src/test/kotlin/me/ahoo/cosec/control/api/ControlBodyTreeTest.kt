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

package me.ahoo.cosec.control.api

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.lifecycle.ControlDerived
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.openjdk.jol.info.GraphLayout

/**
 * `CTL-85`: what a request body becomes as a tree, per byte, at worst: a body of nothing but empty objects, empty arrays,
 * one-character strings, small numbers, or members with empty values, the shapes that make the most nodes of the fewest
 * bytes. Each stays under [ControlDerived.BODY_TREE_FACTOR], the weight the work budget holds for a body before it parses it.
 */
class ControlBodyTreeTest {
    private fun factor(body: String): Double {
        val bytes = body.encodeToByteArray()
        val tree = CoSecJson.mapper.readTree(bytes)
        return GraphLayout.parseInstance(tree).totalSize().toDouble() / bytes.size
    }

    private fun repeated(element: String, count: Int = 100_000) = List(count) { element }.joinToString(",", "[", "]")

    @Test
    fun `no body becomes a tree heavier than the tree factor per byte`() {
        System.setProperty("jol.magicFieldOffset", "true")
        val shapes = mapOf(
            "empty objects" to repeated("{}"),
            "empty arrays" to repeated("[]"),
            "one-character strings" to repeated("\"a\""),
            "distinct numbers" to List(100_000) { it.toString() }.joinToString(",", "[", "]"),
            "members of empty objects" to List(100_000) { "\"$it\":{}" }.joinToString(",", "{", "}"),
            "nested arrays" to "[".repeat(400) + "]".repeat(400),
        )
        val factors = shapes.mapValues { (_, body) -> factor(body) }
        factors.values.forEach {
            it.assert().describedAs("$factors").isLessThanOrEqualTo(ControlDerived.BODY_TREE_FACTOR.toDouble())
        }
    }

    @Test
    fun `a candidate weighs as the document cache weighs a compiled form`() {
        ControlDerived.CANDIDATE_FACTOR.assert().isEqualTo(DocumentCache.COMPILED_FACTOR.toLong())
    }
}
