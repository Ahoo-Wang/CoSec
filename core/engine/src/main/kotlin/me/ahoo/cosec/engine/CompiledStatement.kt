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
import me.ahoo.cosec.engine.pattern.ActionPattern
import me.ahoo.cosec.engine.pattern.ResourcePattern
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.CheckedCondition
import me.ahoo.cosec.model.condition.ConditionInput
import me.ahoo.cosec.model.condition.IndexKeys
import java.util.Collections

/**
 * A statement ready to evaluate (RFC 0005 §6.1). Only [PolicyCompiler] builds one, so every statement in the engine
 * has a parsed action, resource patterns and a checked condition: a statement that does not compile is a compile
 * error, never a statement that matches more than it says.
 *
 * It matches a request when, in this order, one of its [actionPatterns] matches the action, the request's values of
 * the indexed attributes can satisfy its [condition] (its index keys, RFC 0005 §4.1), one of its [resourcePatterns]
 * matches the resource, the policy condition holds, and [condition] holds. Each step stops at the first that does not
 * hold, except that a resource that fails to match (a path that does not decode) still defers to the policy condition:
 * the statement fails unless that condition is false. The first three steps never call host code, so the candidate
 * index can skip a statement that fails one of them and the decision stays the same (I1, I2).
 */
@InternalCoSecApi
class CompiledStatement internal constructor(
    /** Its position in the policy. */
    val index: Int,
    val name: String,
    val effect: Effect,
    private val actions: Array<ActionPattern>,
    /** [ResourcePattern.AnyResource] alone for a statement without resources. */
    internal val resources: Array<ResourcePattern>,
    val condition: CheckedCondition?,
    /** The index attributes [condition] constrains, and [keys] their keys. */
    private val keyAttributes: Array<Attribute>,
    internal val keys: Array<IndexKeys>,
    /** What an external attribute [condition] reads that cannot be fetched means (RFC 0006 §5.4.3). */
    val onMissing: OnMissing = OnMissing.INDETERMINATE,
) {
    /** The external attributes [condition] reads, distinct: what the attribute plan must fetch for it. */
    internal val externals: Array<Attribute> =
        condition?.let { ConditionAttributes.of(it).filter(Attribute::external).distinct().toTypedArray() } ?: NO_ATTRIBUTES

    /** The tokens and literal characters of its resources' glob segments, which the document's budget counts (`DOC-4`). */
    internal val globUnits: Int = resources.sumOf { (it as? ResourcePattern.Http)?.path?.globUnits ?: 0 }

    /** The request headers [condition] reads (`PIP-12`). */
    internal val headers: Array<String> = condition?.let { HeadersRead.of(ConditionAttributes.of(it)) } ?: NO_HEADERS

    /** Whether [condition] reads a captured variable: only then does matching the resource keep the captures. */
    internal val captures: Boolean = condition != null && ConditionAttributes.of(condition).any {
        it.kind == Attribute.Kind.RESOURCE_VAR
    }

    /**
     * Whether [condition] is tested against each matching resource pattern's captures in turn (`ALG-10`): it reads a
     * captured variable, and more than one pattern can capture it. Decided here, so every other statement matches its
     * resource once, as before. For those one corner differs from "one statement per pattern", on purpose: a pattern
     * that fails to match (a path that does not decode) fails the statement only when no other pattern matches, so a
     * statement another pattern matches misses on a false condition rather than failing. The pipeline answers such a
     * path 400 before authorization, so only a host check reaches it.
     */
    internal val eachPattern: Boolean = captures && resources.size > 1

    /** Read-only, made once: the statement is shared by every evaluation (round 2 review L4). */
    val actionPatterns: List<ActionPattern> = Collections.unmodifiableList(actions.toList())

    /** Read-only, made once, as [actionPatterns]. */
    val resourcePatterns: List<ResourcePattern> = Collections.unmodifiableList(resources.toList())

    internal fun keyAttribute(index: Int): Attribute = keyAttributes[index]

    internal fun matchesAction(action: Action): Boolean {
        for (pattern in actions) {
            if (pattern.matches(action)) {
                return true
            }
        }
        return false
    }

    /** Whether [input]'s values of the indexed attributes can satisfy [condition]. Those reads never call the host. */
    internal fun admits(input: ConditionInput): Boolean {
        for (i in keys.indices) {
            if (!keys[i].admits(input.string(keyAttributes[i]))) {
                return false
            }
        }
        return true
    }

    /**
     * Whether [other] means the same: its name, effect, action patterns, resource patterns with their options and
     * condition (by its canonical form) and [onMissing], each compared as a value, in order.
     */
    internal fun sameContent(other: CompiledStatement): Boolean =
        other.name == name && other.effect === effect && other.actions.contentEquals(actions) &&
            other.resources.contentEquals(resources) && other.condition == condition && other.onMissing === onMissing

    override fun toString(): String = "$effect $name ${actionPatterns}${resourcePatterns}${condition ?: ""}"
}

// `internal` compiles to a public static getter of the file's facade, which `PublicSignatures` cannot read as
// internal; the marker says it.
@InternalCoSecApi
internal val NO_ATTRIBUTES: Array<Attribute> = emptyArray()

internal val NO_HEADERS: Array<String> = emptyArray()
