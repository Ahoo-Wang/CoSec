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

package me.ahoo.cosec.context.request

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class RequestPathsTest {

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/public/../admin",
            "/public/%2e%2e/admin",
            "/public/%2E%2E/admin",
            "/public/%2e%2e%2fadmin",
            "/a/./b",
            "/a/%2e/b",
        ],
    )
    fun requireSafeWhenTraversal(path: String) {
        assertThrows<InvalidRequestPathException> { RequestPaths.requireSafe(path) }
    }

    @Test
    fun requireSafeWhenUndecodable() {
        // A malformed escape sequence must fail closed instead of being matched verbatim.
        assertThrows<InvalidRequestPathException> { RequestPaths.requireSafe("/a/%zz/b") }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/",
            "/admin",
            "/api/users%20123",
            "/api/users/123",
        ],
    )
    fun requireSafeWhenNormal(path: String) {
        RequestPaths.requireSafe(path)
    }

    @Test
    fun isTraversal() {
        RequestPaths.isTraversal("/public/../admin").assert().isTrue()
        RequestPaths.isTraversal("/public/%2e%2e/admin").assert().isTrue()
        RequestPaths.isTraversal("/public").assert().isFalse()
    }
}
