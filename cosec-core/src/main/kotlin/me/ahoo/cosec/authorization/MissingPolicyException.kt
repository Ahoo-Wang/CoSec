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

package me.ahoo.cosec.authorization

import me.ahoo.cosec.CoSecException
import me.ahoo.cosec.api.policy.PolicyId

/**
 * Policies listed by [source] that the [PolicyRepository] does not have (deleted, not yet replicated, lost from
 * the store). Authorization fails closed on it: a missing policy may be the deny that keeps the request out, so
 * evaluating without it could widen access. [me.ahoo.cosec.pipeline.SecurityPipeline] maps it to `500`.
 */
class MissingPolicyException(
    val policyIds: Set<PolicyId>,
    val source: Source = Source.PRINCIPAL
) : CoSecException(source.message(policyIds)) {
    /** Who listed the missing policies, which decides where to look for the cause. */
    enum class Source {
        /** The principal (its token, API key or user record) lists policy ids the repository does not return. */
        PRINCIPAL {
            override fun message(policyIds: Set<PolicyId>): String =
                "The principal lists policies $policyIds, which the policy repository does not have."
        },

        /** The repository's global policy index lists ids whose content is missing from the store. */
        GLOBAL_POLICY_INDEX {
            override fun message(policyIds: Set<PolicyId>): String =
                "The global policy index lists policies $policyIds that have no content."
        };

        abstract fun message(policyIds: Set<PolicyId>): String
    }
}
