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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.migrate

import io.lettuce.core.api.sync.RedisCommands
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi

/** The keys stored under a prefix: before RFC 0009, already converted (by hash), or not keys at all. */
class StoredApiKeys(val legacy: List<LegacyApiKey>, val converted: List<String>, val unreadable: List<String>) {
    companion object {
        fun read(
            commands: RedisCommands<String, String>,
            prefix: String,
            scan: (String) -> Sequence<String>
        ): StoredApiKeys {
            val legacy = ArrayList<LegacyApiKey>()
            val converted = ArrayList<String>()
            val unreadable = ArrayList<String>()
            // SCAN may return a key more than once (Redis documents it): one key is read and planned once (#1244).
            scan("$prefix:*").distinct().sorted().forEach { redisKey ->
                val json = commands.get(redisKey) ?: return@forEach
                val hash = redisKey.removePrefix("$prefix:")
                try {
                    if (LegacyApiKey.isConverted(json)) converted += hash else legacy += LegacyApiKey.parse(hash, json)
                } catch (error: IllegalArgumentException) {
                    unreadable += "[$redisKey] is not an API key: ${error.message}"
                }
            }
            return StoredApiKeys(legacy, converted, unreadable)
        }

        /**
         * Rewrites the key under [redisKey] to [json] only while it still holds [expected], what was read (review M2): a
         * key revoked, disabled or changed since is left as it is. True when written.
         */
        fun replace(
            commands: RedisCommands<String, String>,
            redisKey: String,
            expected: String,
            json: String
        ): Boolean =
            commands.eval<Long>(COMPARE_AND_SET, io.lettuce.core.ScriptOutputType.INTEGER, arrayOf(redisKey), expected, json) == 1L

        private const val COMPARE_AND_SET =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then redis.call('SET', KEYS[1], ARGV[2]) return 1 else return 0 end"
    }
}
