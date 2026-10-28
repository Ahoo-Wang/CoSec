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

package me.ahoo.cosec.model.condition

/** The answer of [ConditionContainment.implies]. Callers treat [UNKNOWN] as "not contained". */
enum class Containment {
    /** Every request that satisfies the first condition satisfies the second. */
    YES,

    /** Not proven: the second condition may or may not contain the first. */
    UNKNOWN,
}

/**
 * Condition containment (RFC 0005 §4.2): `implies(c1, c2)` is [Containment.YES] only if every request that satisfies
 * `c1` satisfies `c2`. The grant check of the control plane (RFC 0007 §7.4) relies on it, so it is sound and
 * deliberately incomplete: whatever it cannot prove is [Containment.UNKNOWN] (property A2).
 *
 * Both conditions are normalized first ([ConditionNormalizer]). Then, in order:
 * 1. identical subtrees imply each other;
 * 2. `c2 = and(d...)`: `c1` implies every `d`;
 * 3. `c1 = or(e...)`: every `e` implies `c2`;
 * 4. `c1 = and(e...)`: some `e` implies `c2`, or `c2 = or(d...)` and `c1` implies some `d`;
 * 5. `c2 = or(d...)`: `c1` implies some `d`, or rule 6 holds;
 * 6. `c1` allows finitely many values of one attribute (`eq` without `ignoreCase`, `in`, a `cidr` of single
 *    addresses, `exists` of a Bool) and `c2` reads that attribute only: `c2` holds for each of those values;
 * 7. `not(x)` implies `not(y)` if `y` implies `x` (evaluation is two-valued, §3.4);
 * 8. the atom rules of §4.2 step 6, and attribute comparisons read in either order.
 *
 * Both conditions must come from checkers with the same [ZoneResolver]; identical `time` nodes must also have equal
 * zone tables.
 *
 * Both conditions are read against the same request. A caller that evaluates them for different subjects (the grant
 * check compares the grantee's request with the grantor's) must first substitute every attribute that differs between
 * the two, such as `subject.*`, `scope.roles` and `resource.var.*`: rule 1 proves `x` implies `x` for any atom.
 */
object ConditionContainment {
    fun implies(c1: CheckedCondition, c2: CheckedCondition): Containment {
        val proof = Proof()
        val proven = proof.implies(ConditionNormalizer.normalize(c1), ConditionNormalizer.normalize(c2))
        return if (proven) Containment.YES else Containment.UNKNOWN
    }

    private class Proof {
        /** Results by pair of nodes. Keys compare nodes by identity, so equal renderings never share a result. */
        private val memo = HashMap<Pair<Node, Node>, Boolean>()

        fun implies(c1: CheckedCondition, c2: CheckedCondition): Boolean {
            val key = Pair(Node(c1), Node(c2))
            memo[key]?.let { return it }
            val result = decide(c1, c2)
            memo[key] = result
            return result
        }

        private fun decide(c1: CheckedCondition, c2: CheckedCondition): Boolean = when {
            identical(c1, c2) -> true
            c2 is CheckedCondition.And -> c2.conditions.all { implies(c1, it) }
            c1 is CheckedCondition.Or -> c1.conditions.all { implies(it, c2) }
            c1 is CheckedCondition.And -> c1.conditions.any { implies(it, c2) } ||
                (c2 is CheckedCondition.Or && c2.conditions.any { implies(c1, it) })

            c2 is CheckedCondition.Or -> c2.conditions.any { implies(c1, it) } || byValues(c1, c2)
            c1 is CheckedCondition.Not -> c2 is CheckedCondition.Not && implies(c2.condition, c1.condition)
            else -> byValues(c1, c2) || atoms(c1, c2)
        }
    }

    /** A node compared by identity. */
    private class Node(val condition: CheckedCondition) {
        override fun equals(other: Any?): Boolean = other is Node && other.condition === condition

        override fun hashCode(): Int = condition.hashCode()
    }

    /** Same rendering, and every `time` node in the same position has the same zone table. */
    private fun identical(c1: CheckedCondition, c2: CheckedCondition): Boolean = c1 == c2 && sameZones(c1, c2)

    private fun sameZones(c1: CheckedCondition, c2: CheckedCondition): Boolean = when (c1) {
        is CheckedCondition.Time -> c2 is CheckedCondition.Time && c1.zone == c2.zone
        is CheckedCondition.And -> c2 is CheckedCondition.And && sameZones(c1.conditions, c2.conditions)
        is CheckedCondition.Or -> c2 is CheckedCondition.Or && sameZones(c1.conditions, c2.conditions)
        is CheckedCondition.Not -> c2 is CheckedCondition.Not && sameZones(c1.condition, c2.condition)
        else -> true
    }

    private fun sameZones(c1: List<CheckedCondition>, c2: List<CheckedCondition>): Boolean =
        c1.size == c2.size && c1.indices.all { sameZones(c1[it], c2[it]) }

    /**
     * Rule 6. If [c1] is true only for finitely many values of one attribute, and [c2] reads nothing else, [c2] is
     * evaluated against each of them: `c1` implies `c2` exactly when all of them satisfy it.
     */
    private fun byValues(c1: CheckedCondition, c2: CheckedCondition): Boolean {
        val values = values(c1) ?: return false
        if (reads(c2) != values.attribute) {
            return false
        }
        return values.inputs.all { c2.evaluate(it) }
    }

    /** One input per value [condition] admits for its attribute, or null if they are not finitely many. */
    private fun values(condition: CheckedCondition): Values? = when (condition) {
        is CheckedCondition.Eq -> if (condition.ignoreCase) {
            null
        } else {
            Values(condition.attribute, listOf(Single.string(condition.attribute, condition.value)))
        }

        is CheckedCondition.In -> Values(
            condition.attribute,
            condition.values.map { Single.string(condition.attribute, it) }
        )
        is CheckedCondition.BoolEq -> Values(
            condition.attribute,
            listOf(Single.bool(condition.attribute, condition.value))
        )
        is CheckedCondition.IpEq -> Values(condition.attribute, listOf(Single.ip(condition.attribute, condition.value)))
        is CheckedCondition.Cidr -> {
            val blocks = condition.ranges.blocks
            if (blocks.all { it.prefix == (if (it.address.v4) IPV4_BITS else IPV6_BITS) }) {
                Values(condition.attribute, blocks.map { Single.ip(condition.attribute, it.address) })
            } else {
                null
            }
        }

        is CheckedCondition.Exists -> if (condition.attribute.type == AttributeType.BOOL) {
            Values(condition.attribute, listOf(true, false).map { Single.bool(condition.attribute, it) })
        } else {
            null
        }

        else -> null
    }

    private class Values(val attribute: Attribute, val inputs: List<ConditionInput>)

    /** An input in which [attribute] has one value and every other attribute is absent. */
    private class Single private constructor(
        val attribute: Attribute,
        val string: String?,
        val bool: Boolean?,
        val ip: IpAddress?,
    ) : ConditionInput {
        override fun string(attribute: Attribute): String? = if (attribute == this.attribute) string else null

        override fun bool(attribute: Attribute): Boolean? = if (attribute == this.attribute) bool else null

        override fun stringSet(attribute: Attribute): Set<String>? = null

        override fun ip(attribute: Attribute): IpAddress? = if (attribute == this.attribute) ip else null

        override fun time(attribute: Attribute): Long = ConditionInput.NO_TIME

        companion object {
            fun string(attribute: Attribute, value: String) = Single(attribute, value, null, null)

            fun bool(attribute: Attribute, value: Boolean) = Single(attribute, null, value, null)

            fun ip(attribute: Attribute, value: IpAddress) = Single(attribute, null, null, value)
        }
    }

    /**
     * The one attribute that [condition] reads, or null if it reads more than one. A labels attribute is read
     * through its host.
     */
    private fun reads(condition: CheckedCondition): Attribute? = when (condition) {
        is CheckedCondition.LabelsPrefix -> condition.attribute.host
        is CheckedCondition.Exists -> condition.attribute.host ?: condition.attribute
        is CheckedCondition.Time -> TIME
        is CheckedCondition.AttributeEq -> null
        is CheckedCondition.And -> common(condition.conditions)
        is CheckedCondition.Or -> common(condition.conditions)
        is CheckedCondition.Not -> reads(condition.condition)
        else -> attribute(condition)
    }

    /** The attribute of an atom that reads one attribute and has no host. */
    private fun attribute(condition: CheckedCondition): Attribute? = when (condition) {
        is CheckedCondition.Eq -> condition.attribute
        is CheckedCondition.BoolEq -> condition.attribute
        is CheckedCondition.IpEq -> condition.attribute
        is CheckedCondition.In -> condition.attribute
        is CheckedCondition.Contains -> condition.attribute
        is CheckedCondition.Prefix -> condition.attribute
        is CheckedCondition.Cidr -> condition.attribute
        else -> null
    }

    private fun common(conditions: List<CheckedCondition>): Attribute? {
        val first = reads(conditions.first()) ?: return null
        return if (conditions.all { reads(it) == first }) first else null
    }

    /**
     * Rule 8: atoms that allow infinitely many values. Two `eq` with `ignoreCase` imply each other only when
     * identical (rule 1).
     */
    private fun atoms(c1: CheckedCondition, c2: CheckedCondition): Boolean = when (c2) {
        is CheckedCondition.Exists -> (c2.attribute.host ?: c2.attribute) in required(c1)
        is CheckedCondition.Prefix -> prefix(c1, c2)
        is CheckedCondition.LabelsPrefix -> c1 is CheckedCondition.LabelsPrefix && labels(c1, c2)
        is CheckedCondition.Cidr -> c1 is CheckedCondition.Cidr && cidr(c1, c2)
        is CheckedCondition.Time -> c1 is CheckedCondition.Time && within(c1, c2)
        is CheckedCondition.AttributeEq -> c1 is CheckedCondition.AttributeEq && attributeEq(c1, c2)
        else -> false
    }

    /** More labels are a narrower domain: `com.example.api` implies `com.example`, never `com.examples`. */
    private fun labels(c1: CheckedCondition.LabelsPrefix, c2: CheckedCondition.LabelsPrefix): Boolean =
        c1.attribute == c2.attribute && (c1.labels == c2.labels || c1.labels.startsWith(c2.labels + "."))

    /** Every block of [c1] lies inside one block of [c2]. A block covered only by several together is not proven. */
    private fun cidr(c1: CheckedCondition.Cidr, c2: CheckedCondition.Cidr): Boolean =
        c1.attribute == c2.attribute &&
            c1.ranges.blocks.all { inner -> c2.ranges.blocks.any { outer -> within(inner, outer) } }

    /**
     * The attributes that must be present for [condition] to be true: every atom is false when the attribute it
     * reads is absent (§3.4), and an absent set is empty.
     */
    private fun required(condition: CheckedCondition): Set<Attribute> = when (condition) {
        is CheckedCondition.AttributeEq -> setOf(condition.left, condition.right)
        is CheckedCondition.And, is CheckedCondition.Or, is CheckedCondition.Not -> emptySet()
        else -> setOfNotNull(reads(condition))
    }

    /**
     * `eq` or `prefix` [c1] implies `prefix` [c2] if `c1`'s literal starts with `c2`'s. Folded literals are already
     * in lower case; a case-sensitive `c1` is folded before it is compared with a case-insensitive `c2`. A
     * case-insensitive `c1` never implies a case-sensitive `c2`.
     */
    private fun prefix(c1: CheckedCondition, c2: CheckedCondition.Prefix): Boolean {
        val (attribute, value, ignoreCase) = when (c1) {
            is CheckedCondition.Eq -> Triple(c1.attribute, c1.value, c1.ignoreCase)
            is CheckedCondition.Prefix -> Triple(c1.attribute, c1.value, c1.ignoreCase)
            else -> return false
        }
        if (attribute != c2.attribute || (ignoreCase && !c2.ignoreCase)) {
            return false
        }
        return (if (c2.ignoreCase) Ascii.lowercase(value) else value).startsWith(c2.value)
    }

    /** Whether every address of [inner] is in [outer]. Families never mix. */
    private fun within(inner: CidrBlock, outer: CidrBlock): Boolean =
        inner.address.v4 == outer.address.v4 &&
            compare(outer.startHigh, outer.startLow, inner.startHigh, inner.startLow) <= 0 &&
            compare(inner.endHigh, inner.endLow, outer.endHigh, outer.endLow) <= 0

    private fun compare(aHigh: Long, aLow: Long, bHigh: Long, bLow: Long): Int =
        if (aHigh != bHigh) java.lang.Long.compareUnsigned(aHigh, bHigh) else java.lang.Long.compareUnsigned(aLow, bLow)

    /**
     * `time` [t1] implies `time` [t2] when [t2] is a validity period only (no days, no window) and [t1]'s period
     * lies inside it. Evaluation compares whole milliseconds, so the bounds are compared as it rounds them.
     * Conditions with days or a window imply each other only when identical (rule 1).
     */
    private fun within(t1: CheckedCondition.Time, t2: CheckedCondition.Time): Boolean {
        if (t2.days != DaysOfWeek.ALL || t2.from >= 0) {
            return false
        }
        val notBefore = t2.notBefore?.let { outer ->
            val inner = t1.notBefore ?: return false
            inner.ceilMillis() >= outer.ceilMillis()
        } ?: true
        val notAfter = t2.notAfter?.let { outer ->
            val inner = t1.notAfter ?: return false
            inner.ceilMillis() <= outer.ceilMillis()
        } ?: true
        return notBefore && notAfter
    }

    /** Equality is symmetric, and case-sensitive equality implies case-insensitive equality. */
    private fun attributeEq(c1: CheckedCondition.AttributeEq, c2: CheckedCondition.AttributeEq): Boolean {
        val sameSides = (c1.left == c2.left && c1.right == c2.right) || (c1.left == c2.right && c1.right == c2.left)
        return sameSides && (!c1.ignoreCase || c2.ignoreCase)
    }

    private const val IPV4_BITS = 32
    private const val IPV6_BITS = 128
    private val TIME: Attribute = Attribute.of(Attribute.Kind.ENVIRONMENT_TIME)
}
