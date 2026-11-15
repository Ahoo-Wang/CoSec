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

package me.ahoo.cosec.spring.boot.pipeline

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spring.boot.StartupTask
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * `SNP-3` (RFC 0003 §4.1): every Redis node's `maxmemory-policy` is `noeviction`. A policy that could not be read is
 * treated like an evicting one: a Redis whose `INFO memory` is denied or failed, or a backend this check cannot see
 * into (a custom backend that unwraps to neither CoCache backend), is not known to keep the snapshot, so it fails
 * startup unless told to warn. A wrapper such as `HookedBackend` is read through `CoherenceBackend.unwrap` (CoCache
 * 8.0.0-rc.1).
 *
 * Stricter than CoCache's own check (`StoreEvictionRisk`), which accepts `volatile-*` at startup while every `Store`'s
 * policy has no `ttl`: the revocation tables and the control plane's leases are `Store`s whose entries carry a lifetime
 * of their own, which `volatile-*` may evict before it ends, and an evicted revocation accepts its token again. CoCache
 * counts such a lifetime only once the instance has written or read one.
 */
internal object EvictionPolicyCheck {
    private val log = KotlinLogging.logger {}
    const val NOEVICTION = "noeviction"

    /** What [check] knows about the backend's eviction. */
    sealed interface Reading {
        /** CoCache's in-memory backend: nothing is evicted behind its back. */
        data object NotRedis : Reading

        /** The policies by node, as `RedisBackend.maxmemoryPolicies` reads them from `INFO memory`. */
        data class Policies(val policies: Map<String, String>) : Reading

        data class Unreadable(val reason: String) : Reading
    }

    fun read(backend: CoherenceBackend): Reading {
        val redis = backend as? RedisBackend ?: backend.unwrap(RedisBackend::class.java)
        if (redis != null) {
            return redis.maxmemoryPolicies()?.let { policies ->
                // A cluster connection that knows no upstream node reads nothing (review L3 of #1240).
                if (policies.isEmpty()) Reading.Unreadable(NO_NODE) else Reading.Policies(policies)
            } ?: Reading.Unreadable("INFO memory failed or is not allowed")
        }
        if (backend is InMemoryBackend || backend.unwrap(InMemoryBackend::class.java) != null) {
            return Reading.NotRedis
        }
        return Reading.Unreadable("the CoCache backend ${backend::class.qualifiedName} is not one this check can read")
    }

    private const val NO_NODE = "no Redis node answered INFO memory"

    /** Whether [reading] says nothing of the policy: a failed or denied `INFO`, or no node to ask (review L3 of #1240). */
    fun unreadable(reading: Reading): Boolean =
        reading is Reading.Unreadable || (reading is Reading.Policies && reading.policies.isEmpty())

    /** Why [reading] may let Redis evict the policy snapshot, or null when it cannot. */
    fun problem(reading: Reading): String? = when (reading) {
        Reading.NotRedis -> null
        is Reading.Unreadable -> "Cannot tell whether Redis may evict CoSec's policy snapshot (${reading.reason})"
        is Reading.Policies -> if (reading.policies.isEmpty()) {
            "Cannot tell whether Redis may evict CoSec's policy snapshot ($NO_NODE)"
        } else {
            reading.policies.filterValues { it != NOEVICTION }.takeIf { it.isNotEmpty() }?.let {
                "Redis may evict CoSec's policy snapshot: maxmemory-policy is $it, not $NOEVICTION"
            }
        }
    }

    fun check(reading: Reading, mode: RuntimeAuthorizationProperties.EvictionCheck) {
        val problem = problem(reading) ?: return
        val message = "$problem, so a global DENY could silently vanish " +
            "(${RuntimeAuthorizationProperties.PREFIX}.snapshot.eviction-check)."
        when (mode) {
            RuntimeAuthorizationProperties.EvictionCheck.FAIL -> throw IllegalStateException(message)
            RuntimeAuthorizationProperties.EvictionCheck.WARN -> log.warn {
                "!!! $message Starting anyway (eviction-check=warn). If Redis evicts an app or tenant document, an " +
                    "instance that loads it next reads it as absent: its DENY statements silently stop applying. " +
                    "An evicted platform document still fails every request closed. Set maxmemory-policy to " +
                    "$NOEVICTION, or make sure the policy snapshot can never be evicted."
            }
        }
    }
}
