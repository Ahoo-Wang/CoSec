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

@file:OptIn(me.ahoo.cosec.annotations.InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.control.lifecycle

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.Finding
import me.ahoo.cosec.control.change.Severity
import me.ahoo.cosec.document.PolicyDocument

/** What changing a platform section does to the other documents, which are not compiled again. */
internal object SectionChecks {
    private const val UNPROCESSABLE = 422

    fun sectionWarnings(base: PolicyDocument?, candidate: PolicyDocument, findings: MutableList<Finding>) {
        val limitsChanged = base?.limits?.let(::limitsOf) != candidate.limits?.let(::limitsOf)
        val attributesChanged = base?.attributes?.map { it.path to it.type } != candidate.attributes?.map { it.path to it.type }
        if (limitsChanged || attributesChanged) {
            findings += Finding(
                "V3",
                if (limitsChanged) "$.limits" else "$.attributes",
                "the other documents are not compiled again: one that no longer compiles fails closed on every instance",
                UNPROCESSABLE,
                Severity.WARNING,
            )
        }
    }

    private fun limitsOf(limits: me.ahoo.cosec.model.condition.ConditionLimits) =
        listOf(
            limits.maxNodes,
            limits.maxDepth,
            limits.maxInValues,
            limits.maxCidrBlocks,
            limits.maxLiteralBytes,
            limits.maxCost
        )
}
