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

package me.ahoo.cosec.engine.flat

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.pattern.Captures
import me.ahoo.cosec.engine.pattern.EvaluationScratch
import me.ahoo.cosec.engine.pattern.HttpPath
import me.ahoo.cosec.engine.pattern.ResourceTarget
import me.ahoo.cosec.engine.pattern.ScratchSource
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.model.condition.CheckedCondition
import me.ahoo.cosec.model.condition.ConditionInput
import me.ahoo.cosec.model.condition.IpAddress
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.Obligation
import me.ahoo.cosec.model.decision.StatementRef
import java.util.IdentityHashMap
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.MATCHED
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.ACTION_MISS
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.CONDITION_MISS
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.RESOURCE_MISS
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.GUARD_MISS
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.GUARD_FAILED
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.ERROR
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.NEXT_STATEMENT
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.NEXT_POLICY
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.DECIDED
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.GUARD_MATCHED
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.GUARD_MISSED
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.GUARD_ERROR
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.INITIAL_GUARDS
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.SCAN_GUARDS
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.MAP_GUARDS
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.NOT_REMEMBERED
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.INITIAL_LISTS
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.WORD_BITS
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.WORD_SHIFT
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.PATH_UNPARSED
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.PATH_PARSED
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.PATH_MALFORMED
import me.ahoo.cosec.engine.flat.FlatEvaluator.Companion.EMPTY

// A permit's obligations: the rate limit rules it matched and their buckets.

/**
 * [decision] with its obligations, when it is a permit and a document of the view has rate limit rules (RFC 0006
 * §8.2): every rule that matches the request, in document order (platform, app, tenant) and rule order, with the
 * bucket its key reads. Matching is a statement's: action, resource and condition, each rule with its own captures.
 *
 * A rule whose condition or key reads an external attribute that could not be fetched has no bucket, and its
 * `strictFailure` decides. Any other error makes it strict: it cannot be skipped, so the pipeline refuses (503).
 */
internal fun FlatEvaluator.withObligations(decision: Decision): Decision {
    if (decision !is Decision.Permit || !view.limitsRates) {
        return decision
    }
    var obligations: ArrayList<Obligation>? = null
    obligating = true
    try {
        for (rules in arrayOf(view.platform.rateLimits, view.app?.rateLimits, view.tenant?.rateLimits)) {
            for (rule in rules ?: continue) {
                val obligation = obligation(rule) ?: continue
                (obligations ?: ArrayList<Obligation>().also { obligations = it }) += obligation
            }
        }
    } finally {
        obligating = false
    }
    return obligations?.let { Decision.Permit(decision.by, it) } ?: decision
}

internal fun FlatEvaluator.obligation(rule: CompiledRateLimit): Obligation? {
    val result = step(rule.policy, rule.statement)
    if (result == ERROR || result == GUARD_FAILED) {
        val missing = stepError is AttributeMissing
        if (missing && rule.statement.onMissing == OnMissing.NOMATCH) {
            return null
        }
        return Obligation.RateLimit(rule.id, null, rule.limit, rule.strictFailure || !missing)
    }
    if (result != MATCHED) {
        return null
    }
    val key = rule.key ?: return Obligation.RateLimit(rule.id, CompiledRateLimit.SHARED_BUCKET, rule.limit, rule.strictFailure)
    return bucket(rule, key)?.let { Obligation.RateLimit(rule.id, it, rule.limit, rule.strictFailure) }
        ?: Obligation.RateLimit(rule.id, null, rule.limit, rule.strictFailure || bucketError !is AttributeMissing)
}

/** The bucket [key] names for this request; null when it cannot be read ([bucketError] says why). */
@Suppress("TooGenericExceptionCaught")
internal fun FlatEvaluator.bucket(rule: CompiledRateLimit, key: Attribute): String? = try {
    // The statement captures only for its own condition: a key reading a variable matches with captures here.
    captures = if (key.kind == Attribute.Kind.RESOURCE_VAR) {
        keyCaptures(rule.statement)
    } else {
        null
    }
    val value = if (key.type == AttributeType.IP) ip(key)?.toString() else string(key)
    CompiledRateLimit.keyedBucket(value.orEmpty())
} catch (error: Throwable) {
    if (!catches(error)) {
        throw error
    }
    bucketError = error
    null
} finally {
    captures = null
}
