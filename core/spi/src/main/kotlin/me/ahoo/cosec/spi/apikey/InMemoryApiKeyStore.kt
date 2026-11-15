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

package me.ahoo.cosec.spi.apikey

import me.ahoo.cosec.annotations.InternalCoSecApi

/**
 * API keys held in memory, by hash: the keys the starter reads from `cosec.api-key.keys`, or a test's. The pipeline reads
 * them through [reader].
 *
 * @param keys API keys by hash
 */
class InMemoryApiKeyStore(private val keys: Map<String, ApiKey>) {
    fun findByHash(hash: String): ApiKey? = keys[hash]

    /** The same keys for the pipeline: a map lookup, which never blocks. */
    val reader: ApiKeyReader = ApiKeyReader { keys[it] }
}
