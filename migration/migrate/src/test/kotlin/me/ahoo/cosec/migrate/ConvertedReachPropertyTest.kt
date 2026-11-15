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

package me.ahoo.cosec.migrate

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.RoleExpansion
import me.ahoo.cosec.migrate.ConvertedReach.Companion.HTTP_GET
import me.ahoo.cosec.migrate.ConvertedReach.Companion.ORDER
import me.ahoo.cosec.migrate.ConvertedReach.Companion.ORDER_DELETE
import me.ahoo.cosec.migrate.ConvertedReach.Companion.ORDER_PATH
import me.ahoo.cosec.migrate.ConvertedReach.Companion.security
import me.ahoo.cosec.migrate.Fixtures7x.app
import me.ahoo.cosec.migrate.Fixtures7x.appPermission
import me.ahoo.cosec.migrate.Fixtures7x.permission
import me.ahoo.cosec.migrate.Fixtures7x.policy
import me.ahoo.cosec.migrate.Fixtures7x.source
import me.ahoo.cosec.migrate.Fixtures7x.statement
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.random.Random

/**
 * MIG-2 as a property: whatever 7.x data converts, the deployment it makes permits no action outside `http:`. 7.x
 * decided HTTP requests and nothing else, so neither a control-plane action (`cosec:*`) nor the action of a check in
 * code can be something 7.x granted. MIG-1's oracles compare HTTP requests, on which `*` and `http:*` agree; this one
 * asks the actions they cannot see.
 *
 * The 7.x data is generated, not chosen: actions are built from every 7.x form (patterns, `all` with and without
 * methods, `path` strings, arrays and objects, arrays and composites of any of them, nested), statements of either
 * effect under conditions of [ConditionTranslatorTest.CORPUS], in global, attachable and app permission policies,
 * partitioned either way. Each converted deployment is asked, through [ConvertedReach], every control-plane action for
 * a member of the tenant and one of the platform, and a check in code in an app and in the control app, by principals
 * holding every role and every policy the data can name.
 */
class ConvertedReachPropertyTest {
    private val iterations = System.getProperty("cosec.property.iterations")?.toInt() ?: 400
    private val at = Instant.parse("2026-10-26T00:00:00Z")

    @Test
    fun `no converted deployment permits an action outside http`() {
        var converted = 0
        var everyActionAllowed = 0
        var httpPermitted = 0
        repeat(iterations) { iteration ->
            val case = Case.random(Random(SEED + iteration))
            val conversion = Converter().convert(case.source, ConvertOptions(case.partition, at))
            if (!conversion.report.accepted) {
                return@repeat
            }
            converted += 1
            // What the documents grant, read as everything that reads a grant reads it.
            val allows = conversion.documents.values.map(RoleExpansion::effective).flatMap { it.policies }
                .flatMap { it.statements }.filter { it.effect == Effect.ALLOW }
            allows.flatMap { it.actions }.filterNot { it.startsWith(HTTP) }.assert()
                .describedAs("ALLOW actions outside http: in the conversion of $case")
                .isEmpty()
            if (allows.any { it.actions == listOf("$HTTP*") && it.resources.isEmpty() }) {
                everyActionAllowed += 1
            }
            val reach = ConvertedReach(conversion.documents)
            // Every policy the conversion made attachable to them, and every role: an attachment of a policy that
            // does not exist is unresolved, which permits nothing (fail closed), and would hide what the rest grants.
            val alice = attachable(conversion, SnapshotKey.Tenant(ACME.value))
            val ops = attachable(conversion, SnapshotKey.Tenant(TenantId.PLATFORM.value))
            val acmeMember = security(ACME, "alice", ROLES, alice)
            if (reach.permits(acmeMember, HTTP_GET, ORDER_PATH)) {
                httpPermitted += 1
            }
            reach.controlActions(acmeMember, SnapshotKey.Tenant(ACME.value)).assert()
                .describedAs("control-plane actions a member of acme gets from $case").isEmpty()
            reach.controlActions(security(TenantId.PLATFORM, "ops", ROLES, ops), SnapshotKey.Platform).assert()
                .describedAs("control-plane actions a member of (platform) gets from $case").isEmpty()
            reach.permits(acmeMember, ORDER_DELETE, ORDER).assert()
                .describedAs("order:delete in the control app from $case").isFalse()
            reach.permits(security(ACME, "alice", ROLES, alice, app = AppId("order")), ORDER_DELETE, ORDER).assert()
                .describedAs("order:delete in app order from $case").isFalse()
        }
        // The oracle saw what it guards: conversions, ALLOWs of every action among them, and principals they permit.
        converted.assert().isGreaterThan(iterations / 2)
        everyActionAllowed.assert().isGreaterThan(iterations / 10)
        httpPermitted.assert().isGreaterThan(iterations / 10)
    }

    /** The attachable policies of the platform's document and of [tenant]'s: what a member of it may attach. */
    private fun attachable(conversion: Conversion, tenant: SnapshotKey): List<String> =
        listOfNotNull(conversion.documents[SnapshotKey.Platform], conversion.documents[tenant])
            .flatMap { it.policies }.filter { it.attachable }.map { it.id.value }

    /** 7.x data and how it is partitioned. */
    private class Case(val source: Source7x, val partition: Partition, private val description: String) {
        override fun toString(): String = "$partition $description"

        companion object {
            fun random(random: Random): Case {
                val policies = POLICY_IDS.shuffled(random).take(1 + random.nextInt(POLICY_IDS.size)).map { id ->
                    val type = TYPES.random(random)
                    id to policy(
                        id,
                        type,
                        List(1 + random.nextInt(3)) { statement(random) },
                        condition = condition(random, POLICY_CONDITION_ODDS, holding = listOf(AUTHENTICATED)),
                        tenantId = if (random.nextInt(4) == 0) "(0)" else ACME.value,
                    )
                }
                val globals = policies.filter { (_, json) -> "\"type\":\"global\"" in json }.map { it.first }.toSet()
                val apps = if (random.nextBoolean()) listOf(orderApp(random)) else emptyList()
                val partition = Partition.entries.random(random)
                val description = (policies.map { it.second } + apps.map { app -> app.appPermission?.bytes?.decodeToString() })
                    .joinToString(" ")
                return Case(source(policies, globals, apps), partition, description)
            }

            private fun statement(random: Random): String {
                val condition = condition(random, STATEMENT_CONDITION_ODDS)
                return statement(action(random, condition), effect(random), condition)
            }

            /** Mostly ALLOWs, so that the principals asked are permitted something the oracle could see widen. */
            private fun effect(random: Random) = if (random.nextInt(DENY_ODDS) == 0) "deny" else "allow"

            /**
             * An action for a statement under [condition]. A path condition is the statement's resource, so 7.x data
             * pairs it with every action (RFC 0005 §7.1); convert rejects it beside an action that has resources.
             */
            private fun action(random: Random, condition: String?): String =
                if (condition in PATH_CONDITIONS) EVERY_ACTION.random(random) else action(random, depth = 2)

            private fun orderApp(random: Random): Source7x.App {
                val permissions = PERMISSION_IDS.take(1 + random.nextInt(PERMISSION_IDS.size)).map { id ->
                    val condition = condition(random, STATEMENT_CONDITION_ODDS)
                    permission(id, action(random, condition), effect(random), condition)
                }
                val granted = if (random.nextBoolean()) "[\"*\"]" else "[\"${PERMISSION_IDS.first()}\"]"
                return app("order", appPermission("order", *permissions.toTypedArray()), "admin" to granted)
            }

            /** A 7.x action of any form, nested [depth] deep at most. */
            fun action(random: Random, depth: Int): String = when (random.nextInt(if (depth == 0) 3 else 5)) {
                0 -> "\"${PATTERNS.random(random)}\""
                1 -> """{"all":${ALL_CONFIGS.random(random)}}"""
                2 -> """{"path":${pathConfig(random, methods = true)}}"""
                3 -> members(random, depth).joinToString(",", "[", "]")
                else -> members(random, depth).joinToString(",", """{"composite":[""", "]}")
            }

            /**
             * The members of an array or composite, which convert holds in one statement only when they share their
             * methods (it rejects the others, and the property asks what converts): patterns, `path`s and nested
             * groups without methods, and now and then `all`.
             */
            private fun members(random: Random, depth: Int): List<String> = List(1 + random.nextInt(3)) {
                when (random.nextInt(if (depth <= 1) 3 else 4)) {
                    0 -> "\"${PATTERNS.random(random)}\""
                    1 -> """{"path":${pathConfig(random, methods = false)}}"""
                    2 -> if (random.nextInt(4) == 0) """{"all":{}}""" else "\"${PATTERNS.random(random)}\""
                    else -> members(random, depth - 1).joinToString(",", """{"composite":[""", "]}")
                }
            }

            private fun pathConfig(random: Random, methods: Boolean): String {
                val patterns = List(1 + random.nextInt(3)) { "\"${PATTERNS.random(random)}\"" }
                return when (random.nextInt(if (methods) 4 else 3)) {
                    0 -> patterns.first()
                    1 -> patterns.joinToString(",", "[", "]")
                    2 -> """{"pattern":${patterns.first()}}"""
                    else -> """{"pattern":${patterns.joinToString(",", "[", "]")},"method":${METHODS.random(random)}}"""
                }
            }

            /**
             * No condition, one of [holding], which hold for the principals asked, or any of the corpus but its path
             * conditions (a statement's resource, which [statement] pairs with every action), at [odds] (none, holding).
             */
            private fun condition(random: Random, odds: Pair<Int, Int>, holding: List<String> = HOLDING): String? {
                val roll = random.nextInt(100)
                return when {
                    roll < odds.first -> null
                    roll < odds.first + odds.second -> holding.random(random)
                    else -> CORPUS.random(random)
                }
            }
        }
    }

    private companion object {
        const val SEED = 20261115L
        const val HTTP = "http:"
        val ACME = TenantId("acme")
        val POLICY_IDS = listOf("p0", "p1", "p2")
        val PERMISSION_IDS = listOf("q0", "q1", "q2")
        val TYPES = listOf("global", "custom", "system")
        val PATTERNS = listOf("*", "/orders/**", "/orders/*", "/**", "/a", "/admin/**")
        val ALL_CONFIGS = listOf("{}", "\"*\"", """{"method":"GET"}""", """{"method":["get","POST"]}""")
        val METHODS = listOf("\"GET\"", "[\"get\",\"HEAD\"]", "[\"POST\"]")
        val PATH_CONDITIONS = listOf(
            """{"path":{"part":"request.path","pattern":"/orders/**"}}""",
            """{"bool":{"and":[{"authenticated":{}},{"path":{"part":"request.path","pattern":"/**"}}]}}""",
        )
        const val AUTHENTICATED = """{"authenticated":{}}"""
        val HOLDING = listOf(AUTHENTICATED) + PATH_CONDITIONS
        val CORPUS = ConditionTranslatorTest.CORPUS.map { it.json }.filterNot { "\"path\"" in it }
        val EVERY_ACTION =
            listOf("\"*\"", """{"all":{}}""", """{"path":["*"]}""", """{"composite":[{"all":{}},"/a"]}""")
        const val DENY_ODDS = 8
        val STATEMENT_CONDITION_ODDS = 40 to 40
        val POLICY_CONDITION_ODDS = 80 to 10

        /** Every role the data can grant. */
        val ROLES = listOf("admin", "admin@team")
    }
}
