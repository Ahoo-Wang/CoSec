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

package me.ahoo.cosec.policy.condition.part

import me.ahoo.cosec.api.configuration.Configuration
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.policy.condition.ConditionMatcherFactory

const val REGULAR_CONDITION_MATCHER_TIMEOUT_KEY = "timeout"

/**
 * Default per-match time budget in milliseconds.
 *
 * A policy-supplied pattern is matched against attacker-controlled request input, so a
 * pathological (catastrophic-backtracking) pattern could otherwise hang the worker thread or the
 * reactor event loop. The match is bounded by this budget; on overrun a [RegexTimeoutException] is
 * thrown, which propagates to a fail-closed `IMPLICIT_DENY`. Tune via the `timeout` configuration
 * (milliseconds) — legitimate matches against short request parts complete in microseconds.
 */
const val DEFAULT_REGULAR_CONDITION_MATCHER_TIMEOUT_MILLIS = 1000L

class RegularConditionMatcher(configuration: Configuration) :
    PartConditionMatcher(RegularConditionMatcherFactory.TYPE, configuration) {
    private val pattern: Regex = configuration.getRequired(RegularConditionMatcher::pattern.name).asString()
        .toRegex(RegexOption.IGNORE_CASE)
    private val timeoutMillis: Long =
        configuration.get(REGULAR_CONDITION_MATCHER_TIMEOUT_KEY)?.asLong()
            ?: DEFAULT_REGULAR_CONDITION_MATCHER_TIMEOUT_MILLIS

    override fun matchPart(partValue: String, securityContext: SecurityContext): Boolean {
        return pattern.matchesWithin(partValue, timeoutMillis)
    }
}

class RegularConditionMatcherFactory : ConditionMatcherFactory {
    companion object {
        const val TYPE = "regular"
    }

    override val type: String
        get() = TYPE

    override fun create(configuration: Configuration): ConditionMatcher {
        return RegularConditionMatcher(configuration)
    }
}
