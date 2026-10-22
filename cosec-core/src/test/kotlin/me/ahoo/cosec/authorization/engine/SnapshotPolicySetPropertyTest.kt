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

package me.ahoo.cosec.authorization.engine

import me.ahoo.cosec.api.context.request.AppIdCapable
import me.ahoo.cosec.api.context.request.SpaceIdCapable
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.engine.LayerInput
import me.ahoo.cosec.engine.PolicySet
import me.ahoo.cosec.engine.RuleEntry
import me.ahoo.cosec.engine.UnresolvedGrant
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * P5 (RFC 0003 §8.4): for any generated 7.x data and request, [SnapshotPolicySetLoader] over the compiled snapshot
 * and [PolicySetLoader] over repositories holding the same data build structurally equal [PolicySet]s: the layers'
 * kinds and order, absent reasons, failures, missing policies, the groups' ids, guards and order, and the entries each
 * group visits, in order, twice.
 */
class SnapshotPolicySetPropertyTest {
    private val compiler = PolicySnapshotCompiler()

    private val actions = listOf(
        """"/a/{id}/**"""",
        """"/a/**"""",
        """"/b/**"""",
        """"*"""",
        """["/b/**", "/c/{id}"]""",
        """{ "path": { "pattern": "/c/**", "method": "GET" } }""",
    )
    private val conditions = listOf(
        """{ "all": {} }""",
        """{ "authenticated": {} }""",
        """{ "in": { "part": "request.method", "value": ["GET", "PUT"] } }""",
        """{ "inRole": { "value": "r1@s1" } }""",
        """{ "bool": { "or": [ { "inRole": { "value": "r2" } }, { "eq": { "part": "request.method", "value": "POST" } } ] } }""",
    )

    /** A matcher type no factory knows: the entry decodes as 7.0's cache would fail to. */
    private val unknownCondition = """{ "noSuchMatcher": {} }"""

    private val roles = listOf("r1", "r2", "r3", "r1@s1", "r2@s2", "r3@s1", "bad@", "a@b@c", "")
    private val spaces = listOf("", "s1", "s2")
    private val appIds = listOf("app", "app", "app", "other", "unknown", "a:b")

    private fun Random.effect() = if (nextInt(3) == 0) "deny" else "allow"

    private fun Random.condition(): String = if (nextInt(60) == 0) unknownCondition else conditions.random(this)

    private fun Random.statements(): String = List(nextInt(4)) { index ->
        """{"name":"s$index","effect":"${effect()}","action":${actions.random(this)},"condition":${condition()}}"""
    }.joinToString(",")

    private fun Random.policies(): Map<String, String> {
        val ids = List(nextInt(7)) { "p$it" }
        return ids.associateWith { id ->
            val type = when (nextInt(4)) {
                0 -> "global"
                1 -> "system"
                else -> "custom"
            }
            policyJson(id, type, statements(), if (nextInt(5) == 0) condition() else conditions[0])
        }
    }

    private fun Random.appPermission(appId: String, permissionIds: List<String>): String {
        val permissions = permissionIds.joinToString(",") { id ->
            """{"id":"$id","name":"$id","description":"","effect":"${effect()}","action":${actions.random(
                this
            )},"condition":${condition()}}"""
        }
        return appPermissionJson(appId, permissions, if (nextInt(5) == 0) condition() else conditions[0])
    }

    /** Grants of the bare and spaced roles; long grant lists exceed the shared position cache (64). */
    private fun Random.grants(permissionIds: List<String>, long: Boolean): Map<String, String> {
        val keys = listOf("r1", "r2", "r3", "r1@s1", "r2@s2", "r3@s1", "r1@s2").filter { long || nextInt(4) != 0 }
        return keys.associateWith { key ->
            if (nextInt(50) == 0) {
                return@associateWith """{"not":"an array"}"""
            }
            val granted = when {
                long && (key == "r1" || key == "r2") -> permissionIds.shuffled(
                    this
                ).take(65 + nextInt(permissionIds.size - 64))
                nextInt(6) == 0 -> listOf("*")
                nextInt(6) == 0 -> listOf("perm0", "*")
                else -> (permissionIds + "ghost").shuffled(this).filter { nextBoolean() }
            }
            granted.joinToString(",", "[", "]") { "\"$it\"" }
        }
    }

    private fun Random.data(long: Boolean): LegacyData {
        val policies = if (nextInt(15) == 0) emptyMap() else policies()
        val globalIndex = policies.filterValues { it.contains("\"type\":\"global\"") }.keys.toList()
        val appPermissions = mutableMapOf<String, String>()
        val grants = mutableMapOf<String, Map<String, String>>()
        for (appId in listOf("app", "other")) {
            if (nextInt(6) == 0) {
                continue
            }
            val permissionIds = List(if (long) 66 + nextInt(10) else nextInt(6)) { "perm$it" }
            appPermissions[appId] = if (nextInt(40) == 0) """{"id":"$appId","condition":$unknownCondition,"groups":[]}""" else appPermission(appId, permissionIds)
            grants[appId] = grants(permissionIds, long)
        }
        return LegacyData(policies, globalIndex, appPermissions, grants)
    }

    @Test
    fun snapshotAndRepositoryBuildTheSamePolicySet() {
        val random = Random(SEED)
        var unavailable = 0
        var roleGroups = 0
        repeat(ITERATIONS) { iteration ->
            val long = random.nextInt(8) == 0
            val data = random.data(long)
            val policyIds = data.policies.keys.filter { random.nextBoolean() }.toMutableSet()
            if (random.nextInt(10) == 0) {
                policyIds += "missing"
            }
            val principal = SimplePrincipal(
                id = if (random.nextInt(6) == 0) CoSecPrincipal.ANONYMOUS_ID else "user",
                policies = policyIds,
                roles = roles.filter { random.nextInt(3) == 0 }.toSet() + if (long) setOf("r1", "r2") else emptySet(),
            )
            val request = EvaluateRequest(
                path = "/a/1",
                headers = mapOf(
                    AppIdCapable.APP_ID_KEY to appIds.random(random),
                    SpaceIdCapable.SPACE_ID_KEY to if (long) "" else spaces.random(random),
                ).filterValues { it.isNotEmpty() },
            )
            val context = SimpleSecurityContext(principal)

            val legacyRequest = LegacyFacts.authzRequest(request, context)
            val expected = PolicySetLoader(data.policyRepository, data.appRolePermissionRepository)
                .load(legacyRequest).block()!!

            val facts = LegacyFacts.authzRequest(request, context).host as LegacyFacts
            val reads = SnapshotReads.of(facts)
            val actual = SnapshotPolicySetLoader.load(reads, compiledSnapshot(data, reads.appId, compiler))

            describe(actual).assert().describedAs("iteration $iteration").isEqualTo(describe(expected))
            if (expected.layers.any { it is LayerInput.Unavailable }) unavailable++
            if ((expected.layers[2] as? LayerInput.Loaded)?.groups?.isNotEmpty() == true) roleGroups++
        }
        // The comparison means something only if failures and role groups both occur often.
        unavailable.assert().isBetween(ITERATIONS / 50, ITERATIONS / 2)
        roleGroups.assert().isGreaterThan(ITERATIONS / 4)
    }

    /** The structure of [policies], comparable across loaders: no object identities, only content. */
    private fun describe(policies: PolicySet): List<Any?> = policies.layers.map { layer ->
        when (layer) {
            is LayerInput.Absent -> listOf(layer.kind, "absent", layer.reason)
            is LayerInput.Unavailable -> listOf(layer.kind, "unavailable", rootCause(layer.error).javaClass)
            is LayerInput.Loaded -> listOf(
                layer.kind,
                "loaded",
                layer.missing?.policyIds,
                layer.groups.map { group ->
                    val guard = when (group) {
                        is PolicyGroup -> CoSecJsonSerializer.writeValueAsString(group.policy)
                        is AppPermissionGroup -> CoSecJsonSerializer.writeValueAsString(group.rules.appPermission)
                        else -> error("Unexpected group $group")
                    }
                    val first = entries(group)
                    val second = entries(group)
                    // Both passes of one evaluation must see the very same entries.
                    first.zip(second).forEach { (a, b) -> (a.first === b.first).assert().isTrue() }
                    listOf(group.id, guard, first.map { describe(it.first, it.second) })
                },
            )
        }
    }

    private fun entries(group: me.ahoo.cosec.engine.RuleGroup): List<Pair<RuleEntry, String?>> {
        val entries = ArrayList<Pair<RuleEntry, String?>>()
        group.forEachRule { entry, grantedVia ->
            entries.add(entry to grantedVia)
            false
        }
        return entries
    }

    private fun describe(entry: RuleEntry, grantedVia: String?): List<Any?> = when (entry) {
        is StatementRule -> listOf(
            grantedVia,
            entry.effect,
            entry.index,
            entry.name,
            entry.id,
            entry.policy?.id,
            CoSecJsonSerializer.writeValueAsString(entry.statement),
        )

        is UnresolvedGrant -> listOf(grantedVia, entry.permissionId, entry.error.javaClass, entry.error.message)
        else -> error("Unexpected entry $entry")
    }

    private fun rootCause(error: Throwable): Throwable {
        var cause = error
        while (cause.cause != null && cause.cause !== cause) {
            cause = cause.cause!!
        }
        return cause
    }

    companion object {
        private const val SEED = 20261021
        private const val ITERATIONS = 2_000
    }
}
