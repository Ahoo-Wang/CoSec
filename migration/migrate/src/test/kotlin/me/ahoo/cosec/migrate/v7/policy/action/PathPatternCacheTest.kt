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

package me.ahoo.cosec.migrate.v7.policy.action

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser

class PathPatternCacheTest {
    private val parsed = mutableListOf<String>()

    private fun parse(pattern: String): PathPattern {
        parsed += pattern
        return PathPatternParser.defaultInstance.parse(pattern)
    }

    @Test
    fun `a pattern is parsed once while it is cached`() {
        val cache = PathPatternCache(2)
        val first = cache.get("/orders/{id}", ::parse)
        cache.get("/orders/{id}", ::parse).assert().isSameAs(first)
        parsed.assert().containsExactly("/orders/{id}")
    }

    @Test
    fun `the least recently used pattern is the one that leaves`() {
        val cache = PathPatternCache(2)
        cache.get("/a", ::parse)
        cache.get("/b", ::parse)
        cache.get("/a", ::parse)
        cache.get("/c", ::parse)
        cache.get("/a", ::parse)
        cache.get("/b", ::parse)
        parsed.assert().containsExactly("/a", "/b", "/c", "/b")
    }
}
