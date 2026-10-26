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

/**
 * A condition that passed [ConditionChecker]: every attribute is in the catalog and of the right type, literals are
 * parsed (CIDR blocks, addresses, times) and folded (`ignoreCase`), and the cost is within [ConditionLimits].
 *
 * [evaluate] is total (RFC 0005 §3.3): it never throws for an input that keeps the [ConditionInput] contract, visits
 * each node at most once, and allocates nothing. An absent attribute makes `eq`, `in`, `prefix` and `cidr` false and
 * `exists` false; absent is not equal to anything, another absent value included (§3.4, Q7).
 *
 * Nodes compare by [canonical], a JSON rendering in which `in` values and CIDR blocks are sorted and literals are
 * parsed: two nodes with the same rendering decide every request the same way. It is not the document syntax.
 */
sealed class CheckedCondition(
    /** The canonical rendering. */
    val canonical: String,
    /** The number of nodes (RFC 0005 §3.5). */
    val nodes: Int,
    /** The depth: 1 for a leaf. */
    val depth: Int,
    /** The sum of the node weights (RFC 0005 §3.5). */
    val cost: Int,
) {
    fun evaluate(input: ConditionInput): Boolean = test(input, null)

    /** Evaluates, adding the number of nodes visited to `visits[0]` when [visits] is given. */
    internal abstract fun test(input: ConditionInput, visits: IntArray?): Boolean

    final override fun equals(other: Any?): Boolean = other is CheckedCondition && other.canonical == canonical

    final override fun hashCode(): Int = canonical.hashCode()

    final override fun toString(): String = canonical

    /** `eq` of a [AttributeType.STRING] attribute and a literal, [value] folded when [ignoreCase]. */
    class Eq internal constructor(val attribute: Attribute, val value: String, val ignoreCase: Boolean) :
        CheckedCondition(binary("eq", attribute, quote(value), ignoreCase), 1, 1, Weights.EQ) {
        override fun test(input: ConditionInput, visits: IntArray?): Boolean {
            visit(visits)
            val actual = input.string(attribute) ?: return false
            return if (ignoreCase) Ascii.equalsFolded(actual, value) else actual == value
        }
    }

    /** `eq` of a [AttributeType.BOOL] attribute and a literal. */
    class BoolEq internal constructor(val attribute: Attribute, val value: Boolean) :
        CheckedCondition(binary("eq", attribute, value.toString(), false), 1, 1, Weights.EQ) {
        override fun test(input: ConditionInput, visits: IntArray?): Boolean {
            visit(visits)
            val actual = input.bool(attribute) ?: return false
            return actual == value
        }
    }

    /** `eq` of an [AttributeType.IP] attribute and an address. */
    class IpEq internal constructor(val attribute: Attribute, val value: IpAddress) :
        CheckedCondition(binary("eq", attribute, quote(value.toString()), false), 1, 1, Weights.EQ) {
        override fun test(input: ConditionInput, visits: IntArray?): Boolean {
            visit(visits)
            val actual = input.ip(attribute) ?: return false
            return actual == value
        }
    }

    /** `eq` of two attributes of the same type: false when either is absent. */
    class AttributeEq internal constructor(val left: Attribute, val right: Attribute, val ignoreCase: Boolean) :
        CheckedCondition(binary("eq", left, "{\"attr\":${quote(right.path)}}", ignoreCase), 1, 1, Weights.EQ) {
        override fun test(input: ConditionInput, visits: IntArray?): Boolean {
            visit(visits)
            return when (left.type) {
                AttributeType.STRING -> {
                    val l = input.string(left) ?: return false
                    val r = input.string(right) ?: return false
                    if (ignoreCase) Ascii.equalsIgnoreCase(l, r) else l == r
                }

                AttributeType.BOOL -> {
                    val l = input.bool(left) ?: return false
                    val r = input.bool(right) ?: return false
                    l == r
                }

                AttributeType.IP -> {
                    val l = input.ip(left) ?: return false
                    val r = input.ip(right) ?: return false
                    l == r
                }

                else -> false
            }
        }
    }

    /** `in` of a [AttributeType.STRING] attribute and a set of literals. */
    class In internal constructor(val attribute: Attribute, val values: Set<String>) :
        CheckedCondition(
            binary("in", attribute, values.sorted().joinToString(",", "[", "]", transform = { quote(it) }), false),
            1,
            1,
            Weights.IN
        ) {
        override fun test(input: ConditionInput, visits: IntArray?): Boolean {
            visit(visits)
            val actual = input.string(attribute) ?: return false
            return actual in values
        }
    }

    /** `in` of a literal and a [AttributeType.STRING_SET] attribute: the set contains [value]. */
    class Contains internal constructor(val value: String, val attribute: Attribute) :
        CheckedCondition("{\"in\":[${quote(value)},${quote(attribute.path)}]}", 1, 1, Weights.IN) {
        override fun test(input: ConditionInput, visits: IntArray?): Boolean {
            visit(visits)
            val set = input.stringSet(attribute) ?: return false
            return set.contains(value)
        }
    }

    /** `prefix` of a [AttributeType.STRING] attribute, [value] folded when [ignoreCase]. */
    class Prefix internal constructor(val attribute: Attribute, val value: String, val ignoreCase: Boolean) :
        CheckedCondition(binary("prefix", attribute, quote(value), ignoreCase), 1, 1, Weights.PREFIX) {
        override fun test(input: ConditionInput, visits: IntArray?): Boolean {
            visit(visits)
            val actual = input.string(attribute) ?: return false
            return if (ignoreCase) Ascii.startsWithFolded(actual, value) else actual.startsWith(value)
        }
    }

    /**
     * `prefix` of a [AttributeType.LABELS] attribute: the host's reversed labels start with [labels], whole labels
     * only and in lower case. `com.example` matches `example.com` and `api.example.com`, not `badexample.com`.
     */
    class LabelsPrefix internal constructor(val attribute: Attribute, val labels: String) :
        CheckedCondition(binary("prefix", attribute, quote(labels), false), 1, 1, Weights.PREFIX) {
        private val host: Attribute = requireNotNull(attribute.host)

        /** The host suffix the labels stand for: `com.example` is `example.com`. */
        private val suffix: String = labels.split('.').asReversed().joinToString(".")

        override fun test(input: ConditionInput, visits: IntArray?): Boolean {
            visit(visits)
            val actual = input.string(host) ?: return false
            val start = actual.length - suffix.length
            if (start < 0 || (start > 0 && actual[start - 1] != '.')) {
                return false
            }
            return Ascii.regionMatchesFolded(actual, start, suffix)
        }
    }

    /** `cidr`: the address of an [AttributeType.IP] attribute is in one of the [ranges]. */
    class Cidr internal constructor(val attribute: Attribute, val ranges: IpRanges) :
        CheckedCondition(
            binary("cidr", attribute, ranges.blocks.joinToString(",", "[", "]") { quote(it.toString()) }, false),
            1,
            1,
            Weights.CIDR
        ) {
        override fun test(input: ConditionInput, visits: IntArray?): Boolean {
            visit(visits)
            val actual = input.ip(attribute) ?: return false
            return actual in ranges
        }
    }

    /**
     * `time`, with the semantics of the 7.x `time` condition, read against `environment.time` instead of a clock
     * (RFC 0005 §2 J5): inside `[notBefore, notAfter)` and, when given, the daily window `[from, to)` and the days of
     * the week, in [zone]. A window with `from` after `to` crosses midnight, and its early-morning part belongs to the
     * day it started on.
     */
    class Time internal constructor(
        val zone: ZoneOffsets,
        internal val notBefore: Moment?,
        internal val notAfter: Moment?,
        /** Bit `i` set for day `i`, Monday = 0; [DaysOfWeek.ALL] when the condition names no days. */
        val days: Int,
        /** Nanoseconds of the day, or -1 without a window. */
        val from: Long,
        val to: Long,
    ) : CheckedCondition(render(zone, notBefore, notAfter, days, from, to), 1, 1, Weights.TIME) {
        private val notBeforeMillis: Long = notBefore?.ceilMillis() ?: Long.MIN_VALUE
        private val notAfterMillis: Long = notAfter?.ceilMillis() ?: Long.MAX_VALUE
        private val hasNotAfter: Boolean = notAfter != null
        private val hasWindow: Boolean = from >= 0
        private val local: Boolean = hasWindow || days != DaysOfWeek.ALL

        override fun test(input: ConditionInput, visits: IntArray?): Boolean {
            visit(visits)
            val now = input.time(TIME)
            if (now == ConditionInput.NO_TIME || now < notBeforeMillis) {
                return false
            }
            if (hasNotAfter && now >= notAfterMillis) {
                return false
            }
            if (!local) {
                return true
            }
            val localMillis = now + zone.offsetMillisAt(now)
            var day = DaysOfWeek.of(Math.floorDiv(localMillis, MILLIS_PER_DAY))
            if (hasWindow) {
                val time = Math.floorMod(localMillis, MILLIS_PER_DAY) * NANOS_PER_MILLI
                val crossesMidnight = from > to
                val inWindow = if (crossesMidnight) time >= from || time < to else time >= from && time < to
                if (!inWindow) {
                    return false
                }
                if (crossesMidnight && time < to) {
                    day = (day + 6) % 7
                }
            }
            return (days and (1 shl day)) != 0
        }

        private companion object {
            const val MILLIS_PER_DAY = 86_400_000L
            const val NANOS_PER_MILLI = 1_000_000L
            val TIME: Attribute = Attribute.of(Attribute.Kind.ENVIRONMENT_TIME)

            fun render(
                zone: ZoneOffsets,
                notBefore: Moment?,
                notAfter: Moment?,
                days: Int,
                from: Long,
                to: Long,
            ): String {
                val fields = ArrayList<String>()
                notBefore?.let { fields += "\"notBefore\":${it.ceilMillis()}" }
                notAfter?.let { fields += "\"notAfter\":${it.ceilMillis()}" }
                fields += "\"zone\":${quote(zone.id)}"
                if (days != DaysOfWeek.ALL) {
                    fields += DaysOfWeek.NAMES.filterIndexed { i, _ -> (days and (1 shl i)) != 0 }
                        .joinToString(",", "\"daysOfWeek\":[", "]", transform = { quote(it) })
                }
                if (from >= 0) {
                    fields += "\"from\":$from"
                    fields += "\"to\":$to"
                }
                return fields.joinToString(",", "{\"time\":{", "}}")
            }
        }
    }

    /** `exists`: the attribute is present. For a labels attribute, its host is. */
    class Exists internal constructor(val attribute: Attribute) :
        CheckedCondition("{\"exists\":${quote(attribute.path)}}", 1, 1, Weights.EXISTS) {
        private val read: Attribute = attribute.host ?: attribute

        override fun test(input: ConditionInput, visits: IntArray?): Boolean {
            visit(visits)
            return when (read.type) {
                AttributeType.STRING -> input.string(read) != null
                AttributeType.BOOL -> input.bool(read) != null
                AttributeType.STRING_SET -> input.stringSet(read) != null
                AttributeType.IP -> input.ip(read) != null
                AttributeType.INSTANT -> input.time(read) != ConditionInput.NO_TIME
                AttributeType.LABELS -> false
            }
        }
    }

    class And internal constructor(val conditions: List<CheckedCondition>) :
        CheckedCondition(
            group("and", conditions),
            nodesOf(conditions),
            depthOf(conditions),
            Weights.AND + conditions.sumOf { it.cost }
        ) {
        private val array: Array<CheckedCondition> = conditions.toTypedArray()

        override fun test(input: ConditionInput, visits: IntArray?): Boolean {
            visit(visits)
            for (condition in array) {
                if (!condition.test(input, visits)) {
                    return false
                }
            }
            return true
        }
    }

    class Or internal constructor(val conditions: List<CheckedCondition>) :
        CheckedCondition(
            group("or", conditions),
            nodesOf(conditions),
            depthOf(conditions),
            Weights.OR + conditions.sumOf { it.cost }
        ) {
        private val array: Array<CheckedCondition> = conditions.toTypedArray()

        override fun test(input: ConditionInput, visits: IntArray?): Boolean {
            visit(visits)
            for (condition in array) {
                if (condition.test(input, visits)) {
                    return true
                }
            }
            return false
        }
    }

    class Not internal constructor(val condition: CheckedCondition) :
        CheckedCondition(
            "{\"not\":${condition.canonical}}",
            condition.nodes + 1,
            condition.depth + 1,
            condition.cost + Weights.NOT
        ) {
        override fun test(input: ConditionInput, visits: IntArray?): Boolean {
            visit(visits)
            return !condition.test(input, visits)
        }
    }

    /** The node weights of the cost model (RFC 0005 §3.5). */
    object Weights {
        const val AND: Int = 1
        const val OR: Int = 1
        const val NOT: Int = 1
        const val EXISTS: Int = 1
        const val EQ: Int = 2
        const val PREFIX: Int = 2
        const val IN: Int = 4
        const val CIDR: Int = 4
        const val TIME: Int = 8
    }

    private companion object {
        fun visit(visits: IntArray?) {
            if (visits != null) {
                visits[0]++
            }
        }

        fun binary(operator: String, attribute: Attribute, value: String, ignoreCase: Boolean): String =
            "{\"$operator\":[${quote(attribute.path)},$value]${if (ignoreCase) ",\"ignoreCase\":true" else ""}}"

        fun group(operator: String, conditions: List<CheckedCondition>): String =
            conditions.joinToString(",", "{\"$operator\":[", "]}") { it.canonical }

        fun nodesOf(conditions: List<CheckedCondition>): Int = 1 + conditions.sumOf { it.nodes }

        fun depthOf(conditions: List<CheckedCondition>): Int = 1 + conditions.maxOf { it.depth }

        fun quote(value: String): String {
            val out = StringBuilder(value.length + 2).append('"')
            for (c in value) {
                when {
                    c == '"' -> out.append("\\\"")
                    c == '\\' -> out.append("\\\\")
                    c < ' ' -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    else -> out.append(c)
                }
            }
            return out.append('"').toString()
        }
    }
}
