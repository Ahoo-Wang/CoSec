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

import me.ahoo.cosec.engine.GuardBehavior
import me.ahoo.cosec.engine.MatcherException
import me.ahoo.cosec.engine.RuleBehavior
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.DocumentKind
import kotlin.random.Random

/**
 * Random snapshots and requests over a small vocabulary, so that bindings often apply, policy ids collide between the
 * platform and the tenant, and every kind of decision, error, expiry and missing policy comes up.
 */
class FlatGenerator(private val random: Random) {
    private val fixture = FlatFixture()
    private val tenants = listOf("acme", "beta")
    private val principals = listOf("alice", "bob")
    private val roles = listOf("admin", "editor", "viewer")
    private val guards = List(3) {
        fixture.guard("g$it", pick(GuardBehavior.MATCH to 6, GuardBehavior.MISS to 3, GuardBehavior.THROW to 1))
    }

    private fun <T> pick(vararg weighted: Pair<T, Int>): T {
        var roll = random.nextInt(weighted.sumOf { it.second })
        for ((value, weight) in weighted) {
            roll -= weight
            if (roll < 0) return value
        }
        error("unreachable")
    }

    private fun chance(percent: Int) = random.nextInt(100) < percent

    private fun statement(): Pair<Effect, RuleBehavior> =
        pick(Effect.DENY to 35, Effect.ALLOW to 65) to pick(
            RuleBehavior.MATCH to 20,
            RuleBehavior.ACTION_MISS to 40,
            RuleBehavior.CONDITION_MISS to 25,
            RuleBehavior.THROW to 15,
        )

    private fun policy(id: String, attachable: Boolean): CompiledPolicy {
        val statements = List(random.nextInt(4)) { statement() }.toTypedArray()
        val guard = if (chance(50)) null else guards.random(random)
        return fixture.policy(id, *statements, guard = guard, attachable = attachable)
    }

    private fun expiry(): Long? = pick(null to 8, NOW to 1, NOW + 1 to 1)

    private fun space(): String? = pick(null to 6, "" to 2, "team-a" to 1, "other" to 1)

    fun case(): FlatCase {
        val platformPolicies = List(random.nextInt(5)) { policy("p$it", chance(50)) }
        val sharedId = if (chance(30)) listOf(policy("p0", chance(50))) else emptyList()
        val tenantPolicies = List(random.nextInt(3)) { policy("t$it", chance(50)) } + sharedId
        val appPolicies = List(random.nextInt(4)) { policy("perm:$it", attachable = false) }
        val platformIds = platformPolicies.map { it.id }
        val request = request(platformIds + tenantPolicies.map { it.id } + "perm:0" + "ghost")
        val unavailable = Cause.SourceUnavailable(DocumentKind.entries.random(random), MatcherException("redis"))
        return FlatCase(
            fixture,
            RawDocument(platformPolicies, platformBindings(platformIds)),
            RawDocument(appPolicies, appBindings(appPolicies.map { it.id } + platformIds + "ghost", request)),
            RawDocument(tenantPolicies, tenantBindings(tenantPolicies.map { it.id } + platformIds + "ghost", request)),
            appLoaded = chance(92) && request.scope.app.value.isNotEmpty(),
            tenantLoaded = chance(92),
            unresolved = if (chance(8)) listOf(unavailable) else emptyList(),
            request = request,
        )
    }

    private fun platformBindings(ids: List<String>): List<Binding> =
        buildList {
            if (ids.isNotEmpty()) {
                repeat(random.nextInt(6)) { i ->
                    add(
                        if (chance(60)) {
                            val app = pick(null to 6, "" to 1, "app" to 2, "other" to 1)
                            anyone("b$i", ids.random(random), app, space(), expiry())
                        } else {
                            principal(
                                "b$i",
                                ids.random(random),
                                tenants.random(random),
                                principals.random(random),
                                expiry()
                            )
                        },
                    )
                }
                if (chance(10)) {
                    val policy = ids.random(random)
                    add(principal("bg", policy, tenants.random(random), principals.random(random), NOW + 1, true))
                }
            }
            if (chance(20)) {
                add(root("root", tenants.random(random), principals.random(random), pick(NOW to 1, NOW + 1 to 3)))
            }
        }

    private fun appBindings(ids: List<String>, request: FlatRequest): List<Binding> =
        List(random.nextInt(6)) { i ->
            val app = pick(request.scope.app.value to 6, null to 2, "other" to 1, "" to 1)
            val tenant = pick(null to 6, "acme" to 2, "beta" to 2)
            role("r$i", roles.random(random), ids.random(random), app, space(), tenant, expiry())
        }

    private fun tenantBindings(ids: List<String>, request: FlatRequest): List<Binding> =
        List(random.nextInt(5)) { i ->
            val id = ids.random(random)
            val app = pick(null to 3, request.scope.app.value to 1)
            when (random.nextInt(3)) {
                0 -> anyone("t$i", id, app, space(), expiry())
                1 -> principal("t$i", id, request.scope.tenant.value, principals.random(random), expiry())
                else -> {
                    val tenant = pick(null to 1, request.scope.tenant.value to 1)
                    role("t$i", roles.random(random), id, app, space(), tenant, expiry())
                }
            }
        }

    private fun request(attachable: List<String>): FlatRequest {
        val authenticated = chance(85)
        // ScopeResolver rejects a space the principal has no role in, and an anonymous one has none.
        val space = if (authenticated && chance(25)) "team-a" else ""
        val roleCount = random.nextInt(if (space.isEmpty()) 0 else 1, roles.size + 1)
        return flatRequest(
            principal = principals.random(random),
            tenant = tenants.random(random),
            authenticated = authenticated,
            app = if (chance(85)) "app" else "",
            space = space,
            roles = roles.shuffled(random).take(roleCount),
            attachments = List(random.nextInt(4)) { attachable.random(random) },
            blocked = chance(4),
        )
    }

    /** A document of [tenant] that tries to decide for [case]'s principal and roles. */
    fun hostileTenant(tenant: String, case: FlatCase): CompiledDocument {
        val subject = case.request.request.subject
        val target = case.request.scope.tenant.value
        val policies = listOf(fixture.policy("h-deny", deny()), fixture.policy("h-allow", allow(), attachable = true))
        val ids = policies.map { it.id }
        val bindings = buildList {
            add(principal("h1", ids.random(random), target, subject.id))
            case.request.scope.roles.forEachIndexed { i, role ->
                add(role("h-r$i", role.value, ids.random(random), null, null, target))
            }
            add(anyone("h-any", ids.random(random)))
        }
        return CompiledDocument.tenant(TenantId(tenant), policies, bindings)
    }
}
