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

package me.ahoo.cosec.migrate

import me.ahoo.cosec.migrate.MatcherTranslator.Companion.AND
import me.ahoo.cosec.migrate.MatcherTranslator.Companion.BOOL_TYPE
import me.ahoo.cosec.migrate.MatcherTranslator.Companion.BUILTIN_CONDITION_TYPES
import me.ahoo.cosec.migrate.MatcherTranslator.Companion.EXPRESSION_TYPES
import me.ahoo.cosec.migrate.MatcherTranslator.Companion.NEGATE
import me.ahoo.cosec.migrate.MatcherTranslator.Companion.OR
import me.ahoo.cosec.migrate.MatcherTranslator.Companion.TEMPLATE
import tools.jackson.databind.JsonNode

/**
 * Finds the 7.x configuration shapes whose decisions change in 8.0 by design (RFC 0008 Q3, C1–C6): `verify` is not offered,
 * so the report lists where the data has each shape, and `upgrading.md` explains each change. It reads the 7.x JSON
 * only; nothing here affects what is converted.
 */
internal class SemanticScan(private val record: (SemanticChange) -> Unit) {
    /**
     * A 7.x statement: its effect, action and condition, under a policy (or app) condition. Records C1a for an ALLOW
     * that can fail and C1b for every DENY. D1–D3 are recorded by [ConditionTranslator], which knows the direction.
     */
    fun statement(
        deny: Boolean,
        action: JsonNode?,
        condition: JsonNode?,
        policyCondition: JsonNode?,
        location: String
    ) {
        val canFail = canFail(condition) || canFail(policyCondition) || templated(action)
        if (deny) {
            record(
                SemanticChange(
                    "C1b",
                    location,
                    "DENY statement: when it matches it decides (403/401) even if another statement or layer fails; " +
                        "7.x answered 500 when the failure came first",
                ),
            )
        } else if (canFail) {
            record(
                SemanticChange(
                    "C1a",
                    location,
                    "ALLOW statement whose matchers can fail: an error now counts as no match (7.x: 500)",
                ),
            )
        }
    }

    /** A policy or app condition other than `all`: evaluated only when one of its statements is a candidate. */
    fun policyCondition(condition: JsonNode?, location: String) {
        if (condition == null || condition.isNull || isAll(condition)) {
            return
        }
        record(
            SemanticChange(
                "C1c",
                location,
                "policy-level condition: evaluated when one of its statements is first visited, and an error in it " +
                    "makes its DENY statements fail and its ALLOW statements not match (7.x: 500 for the layer)",
            ),
        )
    }

    /** A grant in a space other than the default: the declared space must now be one of the principal's (C3). */
    fun spacedGrant(roleId: String, spaceId: String, location: String) {
        record(
            SemanticChange(
                "C3",
                location,
                "role [$roleId] granted in space [$spaceId]: a request's declared space must now be confirmed by the " +
                    "principal's memberships (7.x evaluated any declared space)",
            ),
        )
    }

    fun undefinedPermission(permissionIds: List<String>, location: String) {
        record(
            SemanticChange(
                "C6",
                location,
                "grant of permissions $permissionIds the app does not define: rejected by convert (7.x: 500 on use)",
            ),
        )
    }

    /** Root (RFC 0004 §4.8): no longer skips the platform document (C1d), and global DENYs bind it (C2). */
    fun root(globalDenies: List<String>, location: String) {
        record(
            SemanticChange(
                "C1d",
                location,
                "root no longer skips reading the platform document: when it is unavailable, root fails like everyone",
            ),
        )
        globalDenies.forEach { deny ->
            record(
                SemanticChange(
                    "C2",
                    deny,
                    "global DENY statement: it now applies to root as well (7.x: root skipped it)"
                )
            )
        }
    }

    /** `--partition by-owner`: principals of other tenants can no longer attach the policy (C5). */
    fun ownedByTenant(tenantId: String, location: String) {
        record(
            SemanticChange(
                "C5",
                location,
                "attachable policy placed in tenant [$tenantId]'s document: only that tenant's principals can attach it",
            ),
        )
    }

    private fun isAll(condition: JsonNode): Boolean = condition.isObject && condition.size() == 1 &&
        condition.properties().first().let { (type, config) -> type == MatcherTranslator.ALL_TYPE && !negated(config) }

    private fun negated(config: JsonNode): Boolean = config.isObject && config.get(NEGATE)?.asBoolean() == true

    /** Whether a 7.x condition can throw: expressions, regular expressions, templates and custom matchers. */
    private fun canFail(condition: JsonNode?): Boolean {
        if (condition == null || !condition.isObject || condition.size() != 1) {
            return false
        }
        val (type, config) = condition.properties().first().let { it.key to it.value }
        return when {
            type == BOOL_TYPE -> listOf(AND, OR).any { key -> config.get(key)?.values()?.any(::canFail) == true }
            type in EXPRESSION_TYPES || type == "regular" || type !in BUILTIN_CONDITION_TYPES -> true
            else -> config.get("value")?.let { it.isString && it.asString().contains(TEMPLATE) } == true
        }
    }

    /** A path action with a `#{...}` template, which fails when the template cannot be evaluated. */
    private fun templated(action: JsonNode?): Boolean = action != null && action.toString().contains(TEMPLATE)
}
