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

package me.ahoo.cosec.cache

import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.policy.history.PolicyRevision
import me.ahoo.cosec.policy.history.PolicyRevisionStore
import me.ahoo.cosec.policy.history.PolicyRevisions
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Clock

const val DEFAULT_POLICY_HISTORY_KEY_PREFIX = "cosec:policy:history"

/**
 * Policy revisions in Redis: `<keyPrefix>:<policyId>` is a list of revisions as JSON, newest first, trimmed to
 * [maxRevisions]; `<keyPrefix>:<policyId>:seq` numbers them, so concurrent writers on any instance get distinct
 * revision numbers. Calls block; [me.ahoo.cosec.policy.history.VersionedPolicyRepository] makes them off the event
 * loop.
 */
class RedisPolicyRevisionStore(
    private val stringRedisTemplate: StringRedisTemplate,
    keyPrefix: String = DEFAULT_POLICY_HISTORY_KEY_PREFIX,
    private val maxRevisions: Int = PolicyRevisions.DEFAULT_MAX_REVISIONS,
    private val clock: Clock = Clock.systemUTC()
) : PolicyRevisionStore {
    private val keyPrefix = keyPrefix.trimEnd(':')

    init {
        require(maxRevisions > 0) { "maxRevisions must be positive, but was [$maxRevisions]." }
    }

    override fun append(policy: Policy, author: String, note: String): PolicyRevision {
        val revisionNumber = checkNotNull(stringRedisTemplate.opsForValue().increment("${listKey(policy.id)}:seq"))
        val revision = PolicyRevision(policy.id, revisionNumber, clock.instant(), author, note, policy)
        val listOps = stringRedisTemplate.opsForList()
        listOps.leftPush(listKey(policy.id), CoSecJsonSerializer.writeValueAsString(revision))
        listOps.trim(listKey(policy.id), 0, maxRevisions - 1L)
        return revision
    }

    override fun history(policyId: String): List<PolicyRevision> =
        stringRedisTemplate.opsForList().range(listKey(policyId), 0, -1).orEmpty()
            .map { CoSecJsonSerializer.readValue(it, PolicyRevision::class.java) }
            // Concurrent writers may push slightly out of order; the revision number is the truth.
            .sortedByDescending { it.revision }

    private fun listKey(policyId: String): String = "$keyPrefix:$policyId"
}
