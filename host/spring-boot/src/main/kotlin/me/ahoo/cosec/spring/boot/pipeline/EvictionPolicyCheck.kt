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

package me.ahoo.cosec.spring.boot.pipeline

import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.RedisCommandTimeoutException
import io.lettuce.core.RedisConnectionException
import io.lettuce.core.RedisException
import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.api.spi.L1RejectedException
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.cosec.annotations.InternalCoSecApi
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException

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
 *
 * At startup the policy is read as CoCache 8.2.0's own check reads it (CoCache#1009): a read that times out, loses its
 * connection or gets a transient reply is tried again, up to [ATTEMPTS] times with pauses from [FIRST_RETRY_PAUSE]
 * doubling to [MAX_RETRY_PAUSE]; a refused `INFO` is not. One behaviour for both checks, and a starved process starting
 * among many is not failed for a slow answer. The periodic re-check ([monitorRead]) reads once, as CoCache's does: its
 * retry is the next interval. What could not be read is still not safe, and the reason says what happened: a timeout
 * as a timeout, a refusal as a permission.
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

    /** Startup's reading of [backend]: up to [attempts] reads of a Redis backend, [pause] between them. */
    fun read(backend: CoherenceBackend, attempts: Int = ATTEMPTS, pause: (Duration) -> Unit = Thread::sleep): Reading {
        val redis = backend as? RedisBackend ?: backend.unwrap(RedisBackend::class.java)
        if (redis != null) {
            return readPolicies(redis::readMaxmemoryPolicies, attempts, pause)
        }
        if (backend is InMemoryBackend || backend.unwrap(InMemoryBackend::class.java) != null) {
            return Reading.NotRedis
        }
        return Reading.Unreadable("the CoCache backend ${backend::class.qualifiedName} is not one this check can read")
    }

    private const val NO_NODE = "no Redis node answered INFO memory"

    /**
     * The periodic re-check's reading of [backend]: one read, no pause and no line of its own. A pass that waited out
     * pauses would log on every pass of an outage and hold its thread, and a `close` during a pause would interrupt it.
     */
    fun monitorRead(backend: CoherenceBackend): Reading = read(backend, attempts = 1) { }

    /** CoCache 8.2.0's bounds for its own startup check (`StoreEvictionCheck`), so the two checks behave alike. */
    const val ATTEMPTS = 5
    val FIRST_RETRY_PAUSE: Duration = Duration.ofMillis(500)
    val MAX_RETRY_PAUSE: Duration = Duration.ofSeconds(2)

    /** Redis replies that name a passing state (loading, busy, a failover), worth asking again. */
    private val TRANSIENT_REPLIES = listOf("LOADING", "BUSY", "TRYAGAIN", "MASTERDOWN", "CLUSTERDOWN")
    private const val MAX_CAUSE_DEPTH = 8

    /** [policies] read, a transient failure tried again up to [attempts] times; what it could not read, and why. */
    internal fun readPolicies(
        policies: () -> Map<String, String>,
        attempts: Int,
        pause: (Duration) -> Unit,
    ): Reading {
        var attempt = 0
        while (true) {
            attempt++
            val failure: RuntimeException = try {
                val read = policies()
                // A cluster connection that knows no upstream node reads nothing (review L3 of #1240).
                return if (read.isEmpty()) Reading.Unreadable(NO_NODE) else Reading.Policies(read)
            } catch (e: RedisException) {
                e
            } catch (e: L1RejectedException) {
                e
            }
            when {
                refused(failure) -> return Reading.Unreadable(
                    "Redis refused INFO ($failure): grant the Redis user +info"
                )
                !transient(failure) -> return Reading.Unreadable("INFO memory failed ($failure)")
                attempt >= attempts -> return Reading.Unreadable(
                    "Redis did not answer INFO memory ${if (attempts == 1) "($failure)" else "in $attempts attempts (last: $failure)"}: " +
                        "a timeout or a connection failure, not a missing permission; check that Redis is reachable " +
                        "and that this process is not starved of CPU"
                )
            }
            val wait = retryPause(attempt)
            log.warn { "Cannot read maxmemory-policy (INFO memory), attempt $attempt of $attempts - retry in $wait: $failure" }
            pause(wait)
        }
    }

    /** The pause after failed attempt [attempt]: [FIRST_RETRY_PAUSE], doubling, at most [MAX_RETRY_PAUSE]. */
    internal fun retryPause(attempt: Int): Duration {
        val millis = FIRST_RETRY_PAUSE.toMillis() shl (attempt - 1).coerceIn(0, Int.SIZE_BITS - 2)
        return Duration.ofMillis(millis.coerceIn(FIRST_RETRY_PAUSE.toMillis(), MAX_RETRY_PAUSE.toMillis()))
    }

    /** `INFO` refused: an ACL without the permission (`NOPERM`), or a disabled or renamed command. Never retried. */
    internal fun refused(failure: RuntimeException): Boolean {
        val message = failure.message ?: return false
        return failure is RedisCommandExecutionException &&
            (message.startsWith("NOPERM") || message.contains("unknown command", ignoreCase = true))
    }

    /**
     * A failure another attempt may not meet: a command not written (`L1RejectedException`), a connection failure, a
     * timeout, a cancelled command or an I/O error in its causes, or a reply naming a passing state.
     */
    internal fun transient(failure: RuntimeException): Boolean {
        if (failure is L1RejectedException || failure is RedisConnectionException) {
            return true
        }
        if (failure is RedisCommandExecutionException) {
            val message = failure.message ?: return false
            return TRANSIENT_REPLIES.any { message.startsWith(it) }
        }
        return generateSequence<Throwable>(failure) { it.cause }.take(MAX_CAUSE_DEPTH).any {
            it is RedisCommandTimeoutException || it is TimeoutException || it is CancellationException ||
                it is IOException || it.message?.startsWith("Command timed out") == true
        }
    }

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
