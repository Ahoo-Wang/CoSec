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

package me.ahoo.cosec.limiter

/**
 * Supplies the rate limit rules. Called on every allowed request, so implementations must answer from memory.
 */
fun interface RateLimitRuleProvider {
    fun getRules(): List<RateLimitRule>

    companion object {
        fun of(rules: List<RateLimitRule>): RateLimitRuleProvider {
            requireUniqueIds(rules)
            return RateLimitRuleProvider { rules }
        }

        internal fun requireUniqueIds(rules: List<RateLimitRule>) {
            val duplicates = rules.groupBy { it.id }.filterValues { it.size > 1 }.keys
            require(duplicates.isEmpty()) { "Duplicate rate limit rule ids: $duplicates." }
        }
    }
}
