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

package me.ahoo.cosec.engine.pattern

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Resource
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * `PIP-35`: in the deny direction a trailing `/` does not count, of the path or of the pattern; the root keeps its, and
 * an ALLOW's pattern matches the path as written.
 */
class TrailingSeparatorTest {
    private fun deny(pattern: String) = ResourcePattern.parse("http:path:$pattern").inDenyDirection()

    private fun allow(pattern: String) = ResourcePattern.parse("http:path:$pattern")

    private fun target(path: String) = ResourceTarget(Resource("http", path, emptyMap()))

    @Test
    fun `a deny matches the path with or without a trailing separator, either way round`() {
        deny("/orders/{id}").matches(target("/orders/123/")).assert().isTrue()
        deny("/orders/{id}").matches(target("/orders/123")).assert().isTrue()
        deny("/orders/").matches(target("/orders")).assert().isTrue()
        deny("/orders/").matches(target("/orders/")).assert().isTrue()
        deny("/orders/{id}").matches(target("/orders/123/items")).assert().isFalse()
        deny("/orders/{id}").matches(target("/orders/123//")).assert().isFalse()
    }

    @Test
    fun `the captures are those of the path without the separator, when only that matches`() {
        deny("/orders/{id}").match(target("/orders/123/"))!!["id"].assert().isEqualTo("123")
        deny("/files/{*rest}").match(target("/files/a/b/"))!!["rest"].assert().isEqualTo("/a/b/")
    }

    @Test
    fun `the root keeps its separator, and a double star matches both spellings as it did`() {
        deny("/").matches(target("")).assert().isFalse()
        deny("/x").matches(target("/")).assert().isFalse()
        deny("/orders/**").matches(target("/orders/1/")).assert().isTrue()
        deny("/orders/**").matches(target("/orders")).assert().isTrue()
    }

    @Test
    fun `an allow matches the path as written`() {
        allow("/orders/{id}").matches(target("/orders/123/")).assert().isFalse()
        allow("/orders/").matches(target("/orders")).assert().isFalse()
        allow("/orders/{id}").inDenyDirection().matches(target("/orders/123/")).assert().isTrue()
    }
}
