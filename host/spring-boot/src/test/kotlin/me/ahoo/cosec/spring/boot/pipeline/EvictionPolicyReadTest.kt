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

import io.lettuce.core.RedisCommandExecutionException
import io.lettuce.core.RedisCommandTimeoutException
import io.lettuce.core.RedisConnectionException
import io.lettuce.core.RedisException
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

/**
 * `SNP-3`: CoSec's startup eviction check reads the policy as CoCache 8.2.0's own check does (CoCache#1009): a transient
 * failure is tried again within the same bounds, a refused `INFO` is not, and what is still unread is never safe.
 */
class EvictionPolicyReadTest {
    private val pauses = mutableListOf<Duration>()

    /** Reads answering [answers] in turn: a map is a reading, an exception a failed read. */
    private fun read(vararg answers: Any): Pair<EvictionPolicyCheck.Reading, Int> {
        var calls = 0
        val reading = EvictionPolicyCheck.readPolicies({
            val answer = answers[minOf(calls, answers.size - 1)]
            calls++
            @Suppress("UNCHECKED_CAST")
            if (answer is RuntimeException) throw answer else answer as Map<String, String>
        }) { pauses += it }
        return reading to calls
    }

    private val timeout = RedisCommandTimeoutException("Command timed out after 2 second(s)")
    private val safe = mapOf("node" to "noeviction")

    @Test
    fun `a read that times out is tried again, and the next answer decides - SNP-3`() {
        val (reading, calls) = read(timeout, RedisConnectionException("reset"), safe)
        reading.assert().isEqualTo(EvictionPolicyCheck.Reading.Policies(safe))
        calls.assert().isEqualTo(3)
        pauses.assert().containsExactly(Duration.ofMillis(500), Duration.ofSeconds(1))
        EvictionPolicyCheck.check(reading, RuntimeAuthorizationProperties.EvictionCheck.FAIL)
    }

    @Test
    fun `five reads that time out are a timeout, never a safe policy, and fail startup - SNP-3`() {
        val (reading, calls) = read(timeout)
        calls.assert().isEqualTo(EvictionPolicyCheck.ATTEMPTS)
        pauses.assert().containsExactly(
            Duration.ofMillis(500),
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(2),
        )
        reading.assert().isInstanceOf(EvictionPolicyCheck.Reading.Unreadable::class.java)
        val message = assertThrows<IllegalStateException> {
            EvictionPolicyCheck.check(reading, RuntimeAuthorizationProperties.EvictionCheck.FAIL)
        }.message
        message.assert().contains("did not answer INFO memory in 5 attempts").contains("Command timed out")
            .contains("not a missing permission")
    }

    @Test
    fun `a refused INFO is not tried again, and says it is a permission - SNP-3`() {
        val (reading, calls) = read(RedisCommandExecutionException("NOPERM this user has no permissions to run 'info'"))
        calls.assert().isEqualTo(1)
        pauses.assert().isEmpty()
        val message = assertThrows<IllegalStateException> {
            EvictionPolicyCheck.check(reading, RuntimeAuthorizationProperties.EvictionCheck.FAIL)
        }.message
        message.assert().contains("Redis refused INFO").contains("+info")
        // A renamed or disabled INFO is a refusal too.
        read(RedisCommandExecutionException("ERR unknown command 'INFO'")).second.assert().isEqualTo(1)
    }

    @Test
    fun `an evicting policy read on a retry still fails startup - SNP-3`() {
        val evicting = mapOf("node" to "allkeys-lru")
        val (reading, calls) = read(timeout, evicting)
        calls.assert().isEqualTo(2)
        reading.assert().isEqualTo(EvictionPolicyCheck.Reading.Policies(evicting))
        assertThrows<IllegalStateException> {
            EvictionPolicyCheck.check(reading, RuntimeAuthorizationProperties.EvictionCheck.FAIL)
        }.message.assert().contains("may evict")
    }

    @Test
    fun `a failure no retry would change is not tried again, and is still not safe - SNP-3`() {
        val (reading, calls) = read(RedisException("no maxmemory_policy in the reply"))
        calls.assert().isEqualTo(1)
        EvictionPolicyCheck.problem(reading).assert().contains("INFO memory failed")
        // A reply naming a passing state is worth another read.
        read(RedisCommandExecutionException("LOADING Redis is loading the dataset in memory"), safe).second
            .assert().isEqualTo(2)
    }
}
