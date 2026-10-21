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

import me.ahoo.cosec.api.configuration.Configuration
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.AppIdCapable
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.context.request.SpaceIdCapable
import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.permission.PermissionGroup
import me.ahoo.cosec.api.policy.ActionMatcher
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.AuthorizationImpl
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.authorization.RootPrincipalMatcher
import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.configuration.JsonConfiguration
import me.ahoo.cosec.explain.toDecisionRef
import me.ahoo.cosec.parity.DecisionParity
import me.ahoo.cosec.parity.ParityResult
import me.ahoo.cosec.parity.ParityVerdict
import me.ahoo.cosec.permission.AppPermissionData
import me.ahoo.cosec.permission.AppRolePermissionData
import me.ahoo.cosec.permission.PermissionData
import me.ahoo.cosec.permission.PermissionGroupData
import me.ahoo.cosec.permission.RolePermissionData
import me.ahoo.cosec.pipeline.SecurityStatus
import me.ahoo.cosec.policy.BlockingAware
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.policy.StatementData
import me.ahoo.cosec.policy.condition.part.RegexTimeoutException
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import kotlin.random.Random

/**
 * P3 (RFC 0002 §8.4): over random policies, grants, repositories and requests, [AuthorizationImpl.LEGACY] and
 * [AuthorizationImpl.ENGINE] give the same outcome: the same §8.3 tuple through [DecisionParity], the same rule set on
 * the context, and on failure the same message.
 */
class EngineParityPropertyTest {
    /** Always throws [error]: a matcher that fails, as a regex timeout or a bug would. */
    private class ThrowingCondition(private val error: RuntimeException) : ConditionMatcher {
        override val type: String = "throwing"
        override val configuration: Configuration = JsonConfiguration.newPojoConfiguration()
        override fun match(request: Request, securityContext: SecurityContext): Boolean = throw error
    }

    /** A blocking matcher, so the evaluation moves to boundedElastic. */
    private class BlockingCondition(private val result: Boolean) : ConditionMatcher, BlockingAware {
        override val type: String = "blocking"
        override val configuration: Configuration = JsonConfiguration.newPojoConfiguration()
        override val requiresBlocking: Boolean = true
        override fun match(request: Request, securityContext: SecurityContext): Boolean = result
    }

    private fun action(json: String): ActionMatcher = CoSecJsonSerializer.readValue(json, ActionMatcher::class.java)
    private fun condition(json: String): ConditionMatcher =
        CoSecJsonSerializer.readValue(json, ConditionMatcher::class.java)

    /** Actions that capture `id`, so their statements may read `request.path.var.id`. */
    private val capturing = listOf(""""/a/{id}/**"""", """"/a/{id}"""", """["/a/{id}/**", "/b/{id}"]""").map(::action)
    private val other = listOf(
        """"/a/**"""",
        """"/b/**"""",
        """"*"""",
        """"/u/#{principal.id}/**"""",
        """["/b/**", "*"]""",
        """{ "path": { "pattern": "/c/**", "method": "GET" } }""",
    ).map(::action)
    private val conditions = listOf(
        """{ "all": {} }""",
        """{ "authenticated": {} }""",
        """{ "in": { "part": "request.method", "value": ["GET", "PUT"] } }""",
        """{ "startsWith": { "part": "request.path", "value": "/a" } }""",
        """{ "inRole": { "value": "r1" } }""",
        """{ "inRole": { "value": "r1@s1" } }""",
        """{ "eq": { "part": "request.method", "value": "GET", "negate": true } }""",
        """{ "bool": { "or": [ { "inRole": { "value": "r2" } }, { "eq": { "part": "request.method", "value": "POST" } } ] } }""",
    ).map(::condition)
    private val pathVariableConditions = listOf(
        """{ "eq": { "part": "request.path.var.id", "value": "1" } }""",
        """{ "in": { "part": "request.path.var.id", "value": ["1", "2"] } }""",
    ).map(::condition)
    private val guards = listOf(
        """{ "all": {} }""",
        """{ "authenticated": {} }""",
        """{ "startsWith": { "part": "request.path", "value": "/a" } }""",
        """{ "all": { "negate": true } }""",
    ).map(::condition)

    private val paths = listOf("/a/1", "/a/2/x", "/a/3", "/b/1", "/b/x/y", "/u/user/x", "/u/other/x", "/c/x", "/d")
    private val roles = listOf("r1", "r2", "r3", "r1@s1", "r2@s2", "r3@s1", "bad@")
    private val spaces = listOf("", "s1", "s2")

    private fun Random.effect() = if (nextInt(3) == 0) Effect.DENY else Effect.ALLOW

    private fun Random.failingOrBlocking(): ConditionMatcher? = when (nextInt(40)) {
        0 -> ThrowingCondition(IllegalStateException("broken"))
        1 -> ThrowingCondition(RegexTimeoutException("timeout"))
        2 -> BlockingCondition(nextBoolean())
        else -> null
    }

    private fun Random.statement(): Pair<ActionMatcher, ConditionMatcher> {
        val failing = failingOrBlocking()
        if (nextInt(3) == 0) {
            val condition = failing ?: if (nextBoolean()) pathVariableConditions.random(this) else conditions.random(this)
            return capturing.random(this) to condition
        }
        return other.random(this) to (failing ?: conditions.random(this))
    }

    private fun Random.guard(): ConditionMatcher = failingOrBlocking() ?: guards.random(this)

    private fun Random.policy(id: String, type: PolicyType): Policy = PolicyData(
        id = id,
        category = "",
        name = id,
        description = "",
        type = type,
        tenantId = "tenant",
        condition = if (nextInt(4) == 0) guard() else guards[0],
        statements = List(nextInt(5)) { index ->
            val (action, condition) = statement()
            StatementData(name = "s$index", effect = effect(), action = action, condition = condition)
        },
    )

    /**
     * [long]: a catalog of more than 64 permissions, which `r1` and `r2` each grant more than 64 of, in their own
     * orders, so grant positions beyond the shared rule cache differ per role. Sometimes the app permission is not
     * the data class, so its rules are rebuilt per request.
     */
    private fun Random.appRolePermission(long: Boolean): AppRolePermission {
        val permissions = List(if (long) 66 + nextInt(14) else nextInt(6)) { index ->
            val (action, condition) = statement()
            PermissionData(
                id = "perm$index",
                name = "perm$index",
                effect = effect(),
                action = action,
                condition = condition
            )
        }
        val ids = permissions.map { it.id } + "ghost"
        val data = AppPermissionData(
            "app",
            if (nextInt(4) == 0) guard() else guards[0],
            listOf(PermissionGroupData("g", permissions = permissions)),
        )
        val appPermission: AppPermission = if (nextInt(4) == 0) {
            object : AppPermission {
                override val id: String = data.id
                override val condition: ConditionMatcher = data.condition
                override val groups: List<PermissionGroup> = data.groups
            }
        } else {
            data
        }
        return AppRolePermissionData(
            appPermission,
            listOf("r1", "r2", "r3").map { role ->
                val granted = when {
                    long && role != "r3" -> permissions.map { it.id }.shuffled(
                        this
                    ).take(65 + nextInt(permissions.size - 64))
                    else -> when (nextInt(6)) {
                        0 -> listOf("*")
                        1 -> listOf("perm0", "*")
                        else -> ids.shuffled(this).filter { nextInt(8) != 0 || it != "ghost" }.filter { nextBoolean() }
                    }
                }
                RolePermissionData(role, granted.toCollection(LinkedHashSet()))
            },
        )
    }

    /**
     * A repository read that sometimes fails or is empty, answered either synchronously (a scalar Mono, as a local
     * cache hit is) or only on subscription (as a Redis read is).
     */
    private fun <T : Any> Random.read(value: T): Mono<T> = when (nextInt(20)) {
        0 -> Mono.error(IllegalStateException("store down"))
        1 -> Mono.empty()
        else -> Mono.just(value)
    }.let { if (nextBoolean()) it.hide() else it }

    private fun compare(
        repositories: Pair<PolicyRepository, AppRolePermissionRepository>,
        blocked: Boolean,
        root: Boolean,
        request: Request,
        principal: CoSecPrincipal
    ): ParityResult {
        val blacklistChecker = object : BlacklistChecker {
            override fun check(request: Request, context: SecurityContext): Mono<Boolean> = Mono.just(!blocked)
        }
        val rootPrincipalMatcher = RootPrincipalMatcher { root }
        val (policyRepository, appRolePermissionRepository) = repositories
        return DecisionParity(
            AuthorizationImpl.LEGACY.create(
                policyRepository,
                appRolePermissionRepository,
                blacklistChecker,
                rootPrincipalMatcher
            ),
            AuthorizationImpl.ENGINE.create(
                policyRepository,
                appRolePermissionRepository,
                blacklistChecker,
                rootPrincipalMatcher
            ),
        ).compare(request, principal)
    }

    @Test
    fun legacyAndEngineDecideAlike() {
        val random = Random(SEED)
        var failures = 0
        var allowed = 0
        repeat(ITERATIONS) { iteration ->
            val globalPolicies = List(random.nextInt(4)) { random.policy("g$it", PolicyType.GLOBAL) }
            val customPolicies = List(random.nextInt(4)) { random.policy("p$it", PolicyType.CUSTOM) }
            val globalRead = random.read(globalPolicies)
            val principalRead = random.nextInt(20)
            val principalAsync = random.nextBoolean()
            val long = random.nextInt(8) == 0
            val roleRead = random.read(random.appRolePermission(long))
            val policyRepository = object : PolicyRepository {
                override fun getGlobalPolicy(): Mono<List<Policy>> = globalRead
                override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> = when (principalRead) {
                    0 -> Mono.error(IllegalStateException("store down"))
                    1 -> Mono.empty()
                    else -> Mono.just(customPolicies.filter { it.id in policyIds })
                }.let { if (principalAsync) it.hide() else it }

                override fun setPolicy(policy: Policy): Mono<Void> = Mono.empty()
            }
            val appRolePermissionRepository = object : AppRolePermissionRepository {
                override fun getAppRolePermission(
                    appId: String,
                    spaceId: String,
                    roleIds: Set<RoleId>
                ): Mono<AppRolePermission> = roleRead
            }
            val policyIds = customPolicies.filter { random.nextBoolean() }.map { it.id }.toMutableSet()
            if (random.nextInt(15) == 0) {
                policyIds += "missing"
            }
            val principal = SimplePrincipal(
                id = if (random.nextInt(6) == 0) CoSecPrincipal.ANONYMOUS_ID else "user",
                policies = policyIds,
                roles = roles.filter { random.nextInt(3) == 0 }.toSet() + if (long) setOf("r1", "r2") else emptySet(),
            )
            val request = EvaluateRequest(
                path = paths.random(random),
                method = if (random.nextBoolean()) "GET" else "POST",
                headers = mapOf(
                    AppIdCapable.APP_ID_KEY to "app",
                    // The long grants are bare roles, which apply in the default space only.
                    SpaceIdCapable.SPACE_ID_KEY to if (long) "" else spaces.random(random),
                ).filterValues { it.isNotEmpty() },
            )
            val blocked = random.nextInt(25) == 0
            val root = random.nextInt(25) == 0
            val result = compare(policyRepository to appRolePermissionRepository, blocked, root, request, principal)
            val legacy = result.baseline
            val engine = result.candidate
            result.verdict.assert().describedAs(
                "iteration $iteration: $legacy vs $engine"
            ).isEqualTo(ParityVerdict.SAME)
            // Beyond the §8.3 tuple: the rule each side set on its context, and the failure's message.
            engine.verifyContext?.toDecisionRef().assert().describedAs("iteration $iteration")
                .isEqualTo(legacy.verifyContext?.toDecisionRef())
            engine.failure?.message.assert().describedAs("iteration $iteration").isEqualTo(legacy.failure?.message)
            if (legacy.failure != null) failures++
            if (legacy.tuple.status == SecurityStatus.ALLOWED) allowed++
        }
        // Every kind of outcome must occur for the comparison to mean anything.
        failures.assert().isBetween(ITERATIONS / 50, ITERATIONS / 2)
        allowed.assert().isBetween(ITERATIONS / 10, ITERATIONS * 9 / 10)
    }

    companion object {
        private const val SEED = 20261021

        /** `./gradlew :cosec-core:engineParity -PparityIterations=1000000` runs more (RFC 0002 §8.2). */
        private val ITERATIONS = System.getProperty("cosec.parity.iterations")?.toInt() ?: 2_000
    }
}
