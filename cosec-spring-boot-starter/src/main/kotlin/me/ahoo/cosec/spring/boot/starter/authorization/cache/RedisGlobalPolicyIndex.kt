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
package me.ahoo.cosec.spring.boot.starter.authorization.cache

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.api.policy.GlobalPolicyIndex
import me.ahoo.cosec.api.policy.PolicyId
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class RedisGlobalPolicyIndex(
    private val redisTemplate: StringRedisTemplate,
    private val key: String
) : GlobalPolicyIndex {
    private val pendingKey: String = "$key:pending"
    private var lockTtl: Duration = DEFAULT_LOCK_TTL
    private var lockAcquireTimeout: Duration = DEFAULT_LOCK_ACQUIRE_TIMEOUT

    internal constructor(
        redisTemplate: StringRedisTemplate,
        key: String,
        lockTtl: Duration,
        lockAcquireTimeout: Duration
    ) : this(redisTemplate, key) {
        require(!lockTtl.isZero && !lockTtl.isNegative) { "lockTtl must be positive." }
        require(!lockAcquireTimeout.isZero && !lockAcquireTimeout.isNegative) {
            "lockAcquireTimeout must be positive."
        }
        this.lockTtl = lockTtl
        this.lockAcquireTimeout = lockAcquireTimeout
    }

    companion object {
        private val log = KotlinLogging.logger {}
        private val DEFAULT_LOCK_TTL: Duration = Duration.ofSeconds(30)
        private val DEFAULT_LOCK_ACQUIRE_TIMEOUT: Duration = Duration.ofSeconds(10)
        private const val LOCK_RETRY_DELAY_MILLIS = 10L
        private const val MAX_UPDATE_ATTEMPTS = 3
        private val LOCK_RENEWAL_EXECUTOR = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "cosec-global-policy-lock-renewal").apply { isDaemon = true }
        }
        private val RENEW_LOCK_SCRIPT = DefaultRedisScript(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                "return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
            Long::class.java,
        )
        private val RELEASE_LOCK_SCRIPT = DefaultRedisScript(
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                "return redis.call('del', KEYS[1]) else return 0 end",
            Long::class.java,
        )
    }

    override fun getPolicyIds(): Set<PolicyId> =
        redisTemplate.opsForSet().members(key).orEmpty() +
            redisTemplate.opsForSet().members(pendingKey).orEmpty()

    private fun add(policyId: PolicyId) {
        redisTemplate.opsForSet().add(key, policyId)
    }

    private fun remove(policyId: PolicyId) {
        redisTemplate.opsForSet().remove(key, policyId)
    }

    private fun addPending(policyId: PolicyId) {
        redisTemplate.opsForSet().add(pendingKey, policyId)
    }

    private fun removePending(policyId: PolicyId) {
        redisTemplate.opsForSet().remove(pendingKey, policyId)
    }

    override fun update(
        policyId: PolicyId,
        global: Boolean,
        updatePolicy: () -> Unit
    ) {
        val lockKey = "$key:lock:$policyId"
        var ownershipFailure: Throwable? = null
        repeat(MAX_UPDATE_ATTEMPTS) { attempt ->
            val lockToken = UUID.randomUUID().toString()
            acquireLock(lockKey, lockToken)
            val renewalTask = scheduleRenewal(lockKey, lockToken)
            try {
                if (global) {
                    addPending(policyId)
                    add(policyId)
                    updatePolicy()
                } else {
                    updatePolicy()
                    remove(policyId)
                }
                val ownershipConfirmed = runCatching {
                    renewLock(lockKey, lockToken)
                }.onFailure { cause ->
                    ownershipFailure = cause
                    log.warn(cause) { "Failed to confirm policy lock [$lockKey] ownership." }
                }.getOrDefault(false)
                if (ownershipConfirmed) {
                    runCatching { removePending(policyId) }
                        .onFailure { cause ->
                            log.warn(cause) { "Failed to clear pending policy index [$pendingKey:$policyId]." }
                        }
                    return
                }
            } finally {
                renewalTask.cancel(false)
                releaseLock(lockKey, lockToken)
            }
            log.warn {
                "Lost policy lock [$lockKey] during update; retrying [${attempt + 1}/$MAX_UPDATE_ATTEMPTS]."
            }
        }
        val failSafeFailure = runCatching { add(policyId) }.exceptionOrNull()
        if (failSafeFailure != null) {
            ownershipFailure?.let { failSafeFailure.addSuppressed(it) }
            throw IllegalStateException(
                "Lost policy lock [$lockKey] and failed to retain fail-safe index membership.",
                failSafeFailure,
            )
        }
        throw IllegalStateException(
            "Lost policy lock [$lockKey] during all update attempts.",
            ownershipFailure,
        )
    }

    private fun acquireLock(
        lockKey: String,
        lockToken: String
    ) {
        val deadline = System.nanoTime() + lockAcquireTimeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (redisTemplate.opsForValue().setIfAbsent(lockKey, lockToken, lockTtl) == true) {
                return
            }
            try {
                Thread.sleep(LOCK_RETRY_DELAY_MILLIS)
            } catch (interruptedException: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Interrupted while acquiring policy lock [$lockKey].", interruptedException)
            }
        }
        throw IllegalStateException("Timed out acquiring policy lock [$lockKey].")
    }

    private fun scheduleRenewal(
        lockKey: String,
        lockToken: String
    ): ScheduledFuture<*> {
        val renewalIntervalMillis = maxOf(1L, lockTtl.toMillis() / 3)
        return LOCK_RENEWAL_EXECUTOR.scheduleAtFixedRate(
            {
                runCatching { renewLock(lockKey, lockToken) }
                    .onFailure { cause ->
                        log.warn(cause) { "Failed to renew policy lock [$lockKey]." }
                    }
            },
            renewalIntervalMillis,
            renewalIntervalMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun renewLock(
        lockKey: String,
        lockToken: String
    ): Boolean {
        val renewed = redisTemplate.execute(
            RENEW_LOCK_SCRIPT,
            listOf(lockKey),
            lockToken,
            lockTtl.toMillis().toString(),
        )
        return renewed == 1L
    }

    private fun releaseLock(
        lockKey: String,
        lockToken: String
    ) {
        runCatching {
            redisTemplate.execute(RELEASE_LOCK_SCRIPT, listOf(lockKey), lockToken)
        }.onFailure { cause ->
            log.warn(cause) { "Failed to release policy lock [$lockKey]." }
        }
    }
}
