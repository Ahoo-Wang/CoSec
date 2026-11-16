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

package me.ahoo.cosec.engine

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.Evaluator.Companion.ERROR
import me.ahoo.cosec.engine.Evaluator.Companion.GUARD_FAILED
import me.ahoo.cosec.engine.Evaluator.Companion.MATCHED
import me.ahoo.cosec.engine.pattern.Captures
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.Obligation

// A permit's obligations (RFC 0013 §6): the rate limit rules that match, each with the bucket its key reads.

/**
 * [decision] with its obligations, when it is a permit and a document of the view has rate limit rules (RFC 0006
 * §8.2): every rule that matches the request, in document order (platform, app, tenant) and rule order, with the
 * bucket its key reads. Matching is a statement's: action, resource and condition, each rule with its own captures.
 *
 * A rule whose condition or key reads an external attribute that could not be fetched has no bucket, and its
 * `strictFailure` decides. Any other error makes it strict: it cannot be skipped, so the pipeline refuses (503).
 */
internal fun Evaluator.withObligations(decision: Decision): Decision {
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

internal fun Evaluator.obligation(rule: CompiledRateLimit): Obligation? {
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
internal fun Evaluator.bucket(rule: CompiledRateLimit, key: Attribute): String? = try {
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

/**
 * The captures a rule's key reads: those of the first pattern, in declared order, that matches and under whose
 * captures the rule's condition holds (`ALG-10`). The rule matched, so one does; a pattern whose condition fails is
 * not one under which it holds.
 */
@Suppress("TooGenericExceptionCaught")
internal fun Evaluator.keyCaptures(statement: CompiledStatement): Captures? {
    val condition = statement.condition
    for (pattern in statement.resources) {
        val captures = pattern.match(target, this) ?: continue
        if (!statement.eachPattern || condition == null) {
            return captures
        }
        this.captures = captures
        val holds = try {
            condition.evaluate(this)
        } catch (thrown: Throwable) {
            if (!catches(thrown)) {
                throw thrown
            }
            false
        } finally {
            this.captures = null
        }
        if (holds) {
            return captures
        }
    }
    return null
}
