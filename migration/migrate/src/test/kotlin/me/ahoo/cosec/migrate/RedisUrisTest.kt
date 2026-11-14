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

package me.ahoo.cosec.migrate

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** What the tool prints of a Redis URI holds no credentials (review of #1351). */
class RedisUrisTest {
    @Test
    fun `a URI keeps its scheme, hosts, ports, database and query, without its user info`() {
        RedisUris.withoutUserInfo(
            "redis://:s3cret@redis:6379/0?timeout=5s"
        ).assert().isEqualTo("redis://redis:6379/0?timeout=5s")
        RedisUris.withoutUserInfo(
            "rediss://cosec:s3cret@redis.example:6380"
        ).assert().isEqualTo("rediss://redis.example:6380")
        RedisUris.withoutUserInfo("redis://redis:6379").assert().isEqualTo("redis://redis:6379")
    }

    @Test
    fun `each seed node of a cluster loses its user info`() {
        RedisUris.withoutUserInfo("redis://:s3cret@a:7000,redis://:s3cret@b:7001").assert()
            .isEqualTo("redis://a:7000,redis://b:7001")
    }

    @Test
    fun `a password holding what a URI should not is left out whole`() {
        RedisUris.withoutUserInfo("redis://:pa/s ?s@redis:6379").assert().isEqualTo("redis://redis:6379")
    }

    @Test
    fun `a message quoting a URI it was given, or any other, shows no credentials`() {
        val given = "redis://:pa ss@redis:6379"
        RedisUris.scrub("Illegal character in authority at index 9: $given", listOf(given)).assert()
            .doesNotContain("pa ss").contains("redis://redis:6379")
        RedisUris.scrub(
            "Unable to connect to rediss://cosec:other@replica:6380 and redis://:x@b:1",
            emptyList()
        ).assert()
            .doesNotContain("other").doesNotContain(":x@").contains("rediss://replica:6380").contains("redis://b:1")
    }

    /** A password with a comma, a space, `/`, `?` and `@`: a comma is a seed separator only before a scheme. */
    @Test
    fun `a password with a comma, a space, a slash, a question mark and an at sign is left out whole`() {
        val password = "p,a s/s?w@rd"
        RedisUris.withoutUserInfo("redis://:$password@redis:6379/0").assert().isEqualTo("redis://redis:6379/0")
        RedisUris.withoutUserInfo("redis://:$password@a:7000,redis://:$password@b:7001").assert()
            .isEqualTo("redis://a:7000,redis://b:7001")
        val seeds = "rediss://cosec:$password@a:7000, rediss://cosec:$password@b:7001"
        RedisUris.scrub("Cannot connect to $seeds", listOf(seeds)).assert()
            .doesNotContain(
                "p,a"
            ).doesNotContain("w@rd").isEqualTo("Cannot connect to rediss://a:7000, rediss://b:7001")
    }

    @Test
    fun `free text quoting a URI it was not given runs to the last at sign before a space or another seed`() {
        RedisUris.scrub(
            "Unable to connect to redis://:p,a/s?w@rd@h:1,redis://:p,a/s?w@rd@h:2 now",
            emptyList()
        ).assert()
            .isEqualTo("Unable to connect to redis://h:1,redis://h:2 now")
    }
}
