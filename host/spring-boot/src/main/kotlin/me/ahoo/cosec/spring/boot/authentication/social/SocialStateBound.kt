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

package me.ahoo.cosec.spring.boot.authentication.social

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.RedisSocialStateStore
import java.util.Properties

/**
 * How many social sign-ins in progress the deployment holds in Redis (DR1-D-9): a 64th of the memory Redis says it may
 * use (`maxmemory`, or `total_system_memory` when no `maxmemory` is set), counted at [RedisSocialStateStore.STATE_BYTES]
 * a sign-in. Anyone can start a sign-in and Redis must not evict (`SNP-3`), so without a bound a flood of starts fills
 * Redis and every write fails, publishing and revocations included; with it, the flood is refused and the rest of Redis
 * stays CoSec's. Computed, not configured: the memory Redis reports is what the bound protects. Read once, at startup.
 * When Redis reports neither (a managed Redis, or `INFO` refused), [FALLBACK_STATES], with a WARN.
 */
internal object SocialStateBound {
    private val log = KotlinLogging.logger {}

    /** The share of Redis's memory the states may take. */
    const val SHARE: Long = 64

    /**
     * The bound when Redis's memory cannot be read: 100,000 sign-ins, about 50 MB at 512 bytes, far more than a
     * deployment's people start in a state's lifetime, and a small part of any Redis a CoSec deployment runs on.
     */
    const val FALLBACK_STATES: Long = 100_000

    /** The bound from [info], `INFO memory` as Spring reads it (one node, or each node's keys prefixed), and why. */
    fun of(info: () -> Properties?): Long {
        val memory = try {
            info()?.let(::memoryOf)
        } catch (@Suppress("TooGenericExceptionCaught") error: RuntimeException) {
            log.warn { "Cannot read Redis's memory (INFO memory): ${error.message}." }
            null
        }
        if (memory == null) {
            log.warn {
                "Redis reports neither maxmemory nor total_system_memory: social sign-in states are bounded at " +
                    "$FALLBACK_STATES (about ${FALLBACK_STATES * RedisSocialStateStore.STATE_BYTES / MIB} MiB). " +
                    "Allow INFO to CoSec's Redis user (SNP-3 needs it too) for a bound derived from Redis's memory."
            }
            return FALLBACK_STATES
        }
        val bound = (memory.bytes / SHARE / RedisSocialStateStore.STATE_BYTES).coerceAtLeast(1)
        log.info {
            "Social sign-in states: at most $bound in progress in Redis, a ${SHARE}th of ${memory.source} " +
                "(${memory.bytes / MIB} MiB) at ${RedisSocialStateStore.STATE_BYTES} B a sign-in."
        }
        return bound
    }

    private class Memory(val bytes: Long, val source: String)

    /** The smallest positive `maxmemory` of the nodes, else the smallest `total_system_memory`; null when neither. */
    private fun memoryOf(properties: Properties): Memory? {
        fun smallest(name: String): Long? = properties.stringPropertyNames()
            .filter { it == name || it.endsWith(".$name") }
            .mapNotNull { properties.getProperty(it)?.trim()?.toLongOrNull() }
            .filter { it > 0 }
            .minOrNull()
        smallest("maxmemory")?.let { return Memory(it, "maxmemory") }
        return smallest("total_system_memory")?.let { Memory(it, "total_system_memory") }
    }

    private const val MIB = 1L shl 20
}
