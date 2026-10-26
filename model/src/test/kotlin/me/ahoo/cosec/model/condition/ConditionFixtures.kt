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

import java.time.Instant
import java.time.ZoneId

/** A request as plain maps. Reads allocate nothing. */
class MapInput(
    val strings: Map<Attribute, String> = emptyMap(),
    val bools: Map<Attribute, Boolean> = emptyMap(),
    val sets: Map<Attribute, Set<String>> = emptyMap(),
    val ips: Map<Attribute, IpAddress> = emptyMap(),
    val time: Long = ConditionInput.NO_TIME,
) : ConditionInput {
    override fun string(attribute: Attribute): String? {
        check(attribute.type == AttributeType.STRING) { "read $attribute as a String" }
        return strings[attribute]
    }

    override fun bool(attribute: Attribute): Boolean? {
        check(attribute.type == AttributeType.BOOL) { "read $attribute as a Bool" }
        return bools[attribute]
    }

    override fun stringSet(attribute: Attribute): Set<String>? {
        check(attribute.type == AttributeType.STRING_SET) { "read $attribute as a Set<String>" }
        return sets[attribute]
    }

    override fun ip(attribute: Attribute): IpAddress? {
        check(attribute.type == AttributeType.IP) { "read $attribute as an Ip" }
        return ips[attribute]
    }

    override fun time(attribute: Attribute): Long {
        check(attribute.type == AttributeType.INSTANT) { "read $attribute as an Instant" }
        return time
    }
}

fun attr(path: String): Attribute = requireNotNull(Attribute.parse(path)) { path }

fun str(value: String): Literal.Str = Literal.Str(value)

/** Checks [condition] with [checker], failing the test on errors. */
fun checked(condition: Condition, checker: ConditionChecker = ConditionChecker(zones = JavaZones)): CheckedCondition =
    when (val result = checker.check(condition)) {
        is CheckResult.Valid -> result.condition
        is CheckResult.Invalid -> throw AssertionError("expected to compile: ${result.errors}")
    }

fun errors(
    condition: Condition,
    checker: ConditionChecker = ConditionChecker(
        zones = JavaZones
    )
): List<ConditionError> =
    when (val result = checker.check(condition)) {
        is CheckResult.Valid -> throw AssertionError("expected errors, compiled to ${result.condition}")
        is CheckResult.Invalid -> result.errors
    }

/** The number of nodes one evaluation visits. */
fun visits(condition: CheckedCondition, input: ConditionInput): Int {
    val visits = IntArray(1)
    condition.test(input, visits)
    return visits[0]
}

/**
 * Region zones from the JVM's database, as the host will provide them: every transition up to [HORIZON_YEAR].
 * Tests only; `model/` itself may not touch `java.time`.
 */
object JavaZones : ZoneResolver {
    const val HORIZON_YEAR = 2100
    private val cache = HashMap<String, ZoneOffsets?>()

    override fun resolve(id: String): ZoneOffsets? = cache.getOrPut(id) { ZoneResolver.FIXED.resolve(id) ?: load(id) }

    private fun load(id: String): ZoneOffsets? {
        if (id !in ZoneId.getAvailableZoneIds()) {
            return null
        }
        val rules = ZoneId.of(id).rules
        val transitions = rules.transitions.toMutableList()
        val lastYear = transitions.lastOrNull()?.dateTimeBefore?.year ?: 1900
        for (year in lastYear..HORIZON_YEAR) {
            rules.transitionRules.forEach { transitions += it.createTransition(year) }
        }
        val sorted = transitions.distinctBy { it.toEpochSecond() }.sortedBy { it.toEpochSecond() }
            .filter { it.dateTimeBefore.year <= HORIZON_YEAR }
        val first = sorted.firstOrNull()?.offsetBefore ?: rules.getOffset(Instant.EPOCH)
        return ZoneOffsets(
            id,
            LongArray(sorted.size) { sorted[it].toEpochSecond() },
            IntArray(sorted.size + 1) { if (it == 0) first.totalSeconds else sorted[it - 1].offsetAfter.totalSeconds },
        )
    }
}
