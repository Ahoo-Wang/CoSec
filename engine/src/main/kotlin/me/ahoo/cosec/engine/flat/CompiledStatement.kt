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

package me.ahoo.cosec.engine.flat

import me.ahoo.cosec.engine.pattern.ActionPattern
import me.ahoo.cosec.engine.pattern.ResourcePattern
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.CheckedCondition
import me.ahoo.cosec.model.condition.ConditionInput
import me.ahoo.cosec.model.condition.IndexKeys

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

    /** Whether [condition] reads a captured variable: only then does matching the resource keep the captures. */
    internal val captures: Boolean = condition != null && ConditionAttributes.of(condition).any {
        it.kind == Attribute.Kind.RESOURCE_VAR
    }

    val actionPatterns: List<ActionPattern> get() = actions.asList()

    val resourcePatterns: List<ResourcePattern> get() = resources.asList()

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
     * What the statement means, as text: two statements with the same content decide every request alike. Patterns
     * are compared as written with their options, conditions by their canonical form.
     */
    internal val content: String = buildString {
        append(name.length).append(':').append(name).append('|').append(effect).append('|')
        actions.joinTo(this, ",")
        append('|')
        resources.joinTo(this, ",") { resource ->
            if (resource is ResourcePattern.Http) {
                "$resource(${resource.path.caseSensitive},${resource.path.decodeAndParseSegments})"
            } else {
                resource.toString()
            }
        }
        append('|').append(condition?.canonical ?: "").append('|').append(onMissing)
    }

    override fun toString(): String = "$effect $name ${actionPatterns}${resourcePatterns}${condition ?: ""}"
}

/**
 * What a statement means when an external attribute its condition reads cannot be fetched (RFC 0006 §5.4.3): its
 * provider failed or timed out, or the attribute was not in the plan. Attributes that are merely absent are not missing
 * (RFC 0005 §3.4).
 */
enum class OnMissing {
    /**
     * The default. A DENY errs: unless another DENY matches, the decision is `Indeterminate(AttributeUnavailable)`. An
     * ALLOW is skipped, and if nothing permits, the decision is `Indeterminate(AttributeUnavailable)` rather than the
     * implicit deny: never a Permit, only a 503 instead of a 401 or 403.
     */
    INDETERMINATE,

    /**
     * The statement does not match. On a DENY that makes the DENY lapse while a dependency fails, so only the platform
     * document may write it (RFC 0006 Q13).
     */
    NOMATCH,
}

internal val NO_ATTRIBUTES: Array<Attribute> = emptyArray()

/**
 * How [CompiledPolicy] indexes its statements (RFC 0005 §6.2). The decision never depends on them (I2); only how many
 * statements are looked at does.
 */
class IndexOptions(
    /** A policy's statements of one effect are indexed from this many on; fewer are scanned in order. */
    val minStatements: Int = 8,
    /** A partition of the index with more statements than this is split by a condition attribute. */
    val conditionThreshold: Int = 256,
    /**
     * The attributes a large partition may be split by. Only attributes the engine reads itself. A statement's index
     * keys cover every such attribute its condition reads, whichever these are.
     */
    val attributes: List<Attribute> = DEFAULT_ATTRIBUTES,
) {
    init {
        require(minStatements >= 0 && conditionThreshold >= 0) { "Index thresholds must not be negative." }
        for (attribute in attributes) {
            require(indexable(attribute)) { "Conditions cannot be indexed by $attribute." }
        }
    }

    companion object {
        /** RFC 0005 §4.1. */
        val DEFAULT_ATTRIBUTES: List<Attribute> = listOf(
            Attribute.of(Attribute.Kind.SCOPE_APP),
            Attribute.of(Attribute.Kind.SCOPE_SPACE),
            Attribute.of(Attribute.Kind.SUBJECT_TENANT),
            Attribute.of(Attribute.Kind.RESOURCE_TYPE),
        )

        /**
         * String attributes the evaluator answers from the request itself, so reading one never calls the host and
         * never throws: the index may then rule a statement out before anything else of it is evaluated.
         */
        private val INDEXABLE = setOf(
            Attribute.Kind.SUBJECT_TENANT,
            Attribute.Kind.SCOPE_TENANT,
            Attribute.Kind.SCOPE_APP,
            Attribute.Kind.SCOPE_SPACE,
            Attribute.Kind.RESOURCE_TYPE,
            Attribute.Kind.RESOURCE_ATTRIBUTE,
        )

        val DEFAULT: IndexOptions = IndexOptions()

        internal fun indexable(attribute: Attribute): Boolean = attribute.kind in INDEXABLE
    }
}

/** The attributes a condition reads. */
internal object ConditionAttributes {
    fun of(condition: CheckedCondition): List<Attribute> = ArrayList<Attribute>().also { collect(condition, it) }

    private fun collect(condition: CheckedCondition, into: MutableList<Attribute>) {
        when (condition) {
            is CheckedCondition.And -> condition.conditions.forEach { collect(it, into) }
            is CheckedCondition.Or -> condition.conditions.forEach { collect(it, into) }
            is CheckedCondition.Not -> collect(condition.condition, into)
            is CheckedCondition.AttributeEq -> {
                into += condition.left
                into += condition.right
            }

            else -> into += leaf(condition)
        }
    }

    private fun leaf(condition: CheckedCondition): Attribute =
        when (condition) {
            is CheckedCondition.Eq -> condition.attribute
            is CheckedCondition.BoolEq -> condition.attribute
            is CheckedCondition.IpEq -> condition.attribute
            is CheckedCondition.In -> condition.attribute
            is CheckedCondition.Contains -> condition.attribute
            is CheckedCondition.Prefix -> condition.attribute
            is CheckedCondition.LabelsPrefix -> condition.attribute
            is CheckedCondition.Cidr -> condition.attribute
            is CheckedCondition.Exists -> condition.attribute
            is CheckedCondition.Time -> TIME
            else -> throw IllegalArgumentException("Not a leaf: $condition")
        }

    private val TIME = Attribute.of(Attribute.Kind.ENVIRONMENT_TIME)
}
