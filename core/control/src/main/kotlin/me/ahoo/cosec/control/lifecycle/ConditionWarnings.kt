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

package me.ahoo.cosec.control.lifecycle

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ControlStatus.UNPROCESSABLE
import me.ahoo.cosec.control.change.Finding
import me.ahoo.cosec.control.change.Severity
import me.ahoo.cosec.document.DocumentCompileException
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.RoleExpansion
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.ConditionLints

/**
 * `DOC-9` in the direction the compiler lets pass: `ignoreCase` on a letter beyond ASCII where the miss only narrows (an
 * ALLOW's condition, a DENY's under a `not`). The candidate compiles; the author is told that it matches fewer spellings
 * than it reads. What refuses is the compiler's, reported as `COMPILE`.
 */
internal object ConditionWarnings {
    fun check(candidate: PolicyDocument, findings: MutableList<Finding>) {
        // Roles that do not expand are a COMPILE error already reported: nothing to warn about in what they would write.
        val expansion = try {
            RoleExpansion.expand(candidate)
        } catch (_: DocumentCompileException) {
            return
        }
        for ((i, policy) in expansion.document.policies.withIndex()) {
            // A derived policy's one statement is what its catalog entry writes: the entry is where it is.
            val path = expansion.policyPath(i)
            val written = if (expansion.derivedPolicy(i)) ".statements[0]" else ""
            val denies = policy.statements.any { it.effect === Effect.DENY }
            val allows = policy.statements.any { it.effect === Effect.ALLOW }
            policy.condition?.let { warn(it, "$path.condition", denies, allows, findings) }
            for ((s, statement) in policy.statements.withIndex()) {
                val deny = statement.effect === Effect.DENY
                val at = "$path.statements[$s]".removeSuffix(written)
                statement.condition?.let { warn(it, "$at.condition", deny, !deny, findings) }
            }
        }
        for ((r, rule) in candidate.rateLimits.withIndex()) {
            rule.statement.condition?.let { warn(it, "$.rateLimits[$r].condition", true, false, findings) }
        }
    }

    private fun warn(
        condition: Condition,
        path: String,
        fewerAdmits: Boolean,
        moreAdmits: Boolean,
        findings: MutableList<Finding>,
    ) {
        for (lint in ConditionLints.check(condition, fewerAdmits, moreAdmits)) {
            if (!lint.refuses) {
                findings += Finding("COMPILE", path + lint.path.removePrefix("$"), lint.message, UNPROCESSABLE, Severity.WARNING)
            }
        }
    }
}
