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

package me.ahoo.cosec.policy

import me.ahoo.cosec.api.context.SecurityContext

/**
 * Marks the [SecurityContext] of a policy validation dry run (see [DefaultPolicyEvaluator]).
 *
 * Validation runs every condition against a mock request, so a custom matcher with an unavoidable external side
 * effect must check [isPolicyEvaluation] and skip that side effect. Built-in matchers have none: rate limits are
 * spent by `RateLimitingAuthorization`, which validation never runs. The marker travels in the context, so it
 * also reaches matchers nested inside composites.
 *
 * The attribute value is this object, compared by identity: policy data and request input cannot produce it, so
 * a real request can never be mistaken for a dry run.
 */
object PolicyEvaluation {
    const val ATTRIBUTE_KEY = "COSEC_POLICY_EVALUATION"
}

fun SecurityContext.markPolicyEvaluation(): SecurityContext =
    setAttributeValue(PolicyEvaluation.ATTRIBUTE_KEY, PolicyEvaluation)

val SecurityContext.isPolicyEvaluation: Boolean
    get() = attributes[PolicyEvaluation.ATTRIBUTE_KEY] === PolicyEvaluation
