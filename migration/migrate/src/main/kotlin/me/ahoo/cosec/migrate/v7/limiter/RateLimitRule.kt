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

package me.ahoo.cosec.migrate.v7.limiter

import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.migrate.v7.api.context.request.Request
import me.ahoo.cosec.migrate.v7.api.policy.ActionMatcher
import me.ahoo.cosec.migrate.v7.api.policy.ConditionMatcher
import me.ahoo.cosec.migrate.v7.policy.PathVariableScope
import me.ahoo.cosec.migrate.v7.policy.clearPathVariablesBeforeMatch
import me.ahoo.cosec.migrate.v7.policy.condition.AllConditionMatcher
import me.ahoo.cosec.migrate.v7.policy.condition.part.DefaultPartExtractor
import me.ahoo.cosec.migrate.v7.policy.condition.part.PartExtractor
import me.ahoo.cosec.spi.ratelimit.RateLimit

/**
 * Limits the requests matching [action] and [condition] to [rateLimit].
 *
 * The quota is identified by [id], never by its settings, so two rules never share counters by accident.
 *
 * @param key the request or context part (e.g. `request.remoteIp`, `context.principal.id`) that splits the quota
 * into one bucket per value; null shares one bucket among all matching requests. Requests whose part is empty
 * share one bucket, so omitting a header cannot escape the limit.
 * @param strictFailure whether a backend failure denies the request (true) or skips this rule (false)
 */
internal data class RateLimitRule(
    val id: String,
    val action: ActionMatcher,
    val rateLimit: RateLimit,
    val condition: ConditionMatcher = AllConditionMatcher.INSTANCE,
    val key: String? = null,
    val strictFailure: Boolean = false
) {
    companion object {
        private val ID_PATTERN = Regex("[A-Za-z0-9_.-]+")
        private const val SHARED_BUCKET = "global"
        private const val KEYED_BUCKET_PREFIX = "g:"
    }

    init {
        require(ID_PATTERN.matches(id)) { "Rate limit rule id [$id] must match ${ID_PATTERN.pattern}." }
        require(key == null || key.isNotBlank()) { "Rate limit rule [$id] key must not be blank." }
        // An unsupported key would fail every matching request with 500, after earlier rules spent their permits.
        require(key == null || DefaultPartExtractor.isSupported(key)) {
            "Rate limit rule [$id] key [$key] is not a supported part (e.g. request.remoteIp, context.principal.id)."
        }
        PathVariableScope.requireScoped(id, action, condition, key)
    }

    private val keyExtractor: PartExtractor? = key?.let { DefaultPartExtractor(it) }

    /**
     * Matches seeing only the path variables this rule's own action captures. [bucketOf] reads the same
     * variables as long as it runs before the next rule matches, which clears them.
     */
    fun matches(request: Request, securityContext: SecurityContext): Boolean {
        action.clearPathVariablesBeforeMatch(securityContext)
        return action.match(request, securityContext) && condition.match(request, securityContext)
    }

    /**
     * The bucket of this request. Keyed buckets carry a prefix, so a key value literally named `global` cannot
     * collide with the shared bucket.
     */
    fun bucketOf(request: Request, securityContext: SecurityContext): String {
        val extractor = keyExtractor ?: return SHARED_BUCKET
        return KEYED_BUCKET_PREFIX + extractor.extract(request, securityContext)
    }
}
