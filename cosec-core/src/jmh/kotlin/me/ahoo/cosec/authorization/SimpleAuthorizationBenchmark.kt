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

package me.ahoo.cosec.authorization

import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.request.AppIdCapable
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.authorization.engine.CompiledApp
import me.ahoo.cosec.authorization.engine.CompiledPolicies
import me.ahoo.cosec.authorization.engine.PolicySnapshotCompiler
import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.permission.AppPermissionData
import me.ahoo.cosec.permission.AppRolePermissionData
import me.ahoo.cosec.permission.PermissionData
import me.ahoo.cosec.permission.PermissionGroupData
import me.ahoo.cosec.permission.RolePermissionData
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.policy.StatementData
import me.ahoo.cosec.policy.action.PathActionMatcherFactory
import me.ahoo.cosec.policy.condition.AllConditionMatcher
import me.ahoo.cosec.policy.condition.CONDITION_MATCHER_NEGATE_KEY
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.serialization.CoSecModule
import me.ahoo.cosec.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.SnapshotOptions
import me.ahoo.cosec.snapshot.SnapshotSource
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import reactor.core.publisher.Mono
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.Duration
import java.time.Instant

/**
 * Authorization hot path with in-memory repositories.
 *
 * The role repository rebuilds [AppRolePermissionData] per call, as the Redis-backed repository does.
 *
 * Scenarios:
 * - `global`: matched by a global ALLOW (all layers still evaluated for explicit denies)
 * - `role`: matched by a role permission near the end of the app's permission list
 * - `none`: nothing matches, full traversal ending in an implicit deny
 * - `global-deny`: denied by a global DENY while the principal and role layers still have data (RFC 0002 X1)
 * - `role-heavy`: 12 roles granting 30 permissions each, plus a `*` grant over a 120-permission catalog; nothing
 *   matches, so every grant is visited in both passes
 * - `guard-miss`: every policy condition and the app condition miss, so only the guards run
 *
 * [source] picks the implementation and where it reads its policies, one value per combination that exists:
 * - `legacy`, `legacy-deferred`: [SimpleAuthorization] over the repositories (RFC 0002 §7.2 step 4);
 * - `engine`, `engine-deferred`: [EngineAuthorization] over the same repositories;
 * - `snapshot`: the engine over a loaded policy snapshot of the same data (RFC 0003 §9), through
 *   [SnapshotEngineAuthorization] (test assembly until RFC 0003 PR-5);
 * - `engine-mixed`, `engine-deferred-mixed`, `snapshot-mixed`: the same, with every eighth call a [TraceLevel.FULL]
 *   evaluation, so the untraced calls run with a call site that has seen both evaluators. `legacy` has no trace
 *   level, so it has no `-mixed` value: the matrix holds no duplicate cell.
 *
 * `-deferred` repositories answer like the Redis repositories do, through `Mono.defer`, instead of a ready `Mono.just`.
 * Setup checks that every source decides the scenario alike: the same result, by the same policy and statement or
 * the same role and permission.
 *
 * [globalPolicies] adds global policies that never match, to show that a snapshot's allocation does not grow with
 * them: run the benchmark jar with `-p source=snapshot -p scenario=none -p globalPolicies=10,100,1000`.
 */
@State(Scope.Benchmark)
open class SimpleAuthorizationBenchmark {
    @Param("global", "role", "none", "global-deny", "role-heavy", "guard-miss")
    lateinit var scenario: String

    @Param(
        "legacy",
        "legacy-deferred",
        "engine",
        "engine-deferred",
        "snapshot",
        "engine-mixed",
        "engine-deferred-mixed",
        "snapshot-mixed",
    )
    lateinit var source: String

    /** The number of global policies; 3 is the scenarios' own. */
    @Param("3")
    var globalPolicies: Int = 3

    private var snapshotSource: SnapshotSource<CompiledPolicies, CompiledApp>? = null
    private lateinit var authorization: Authorization
    private lateinit var traced: Authorization
    private var calls = 0
    private lateinit var request: EvaluateRequest
    private lateinit var securityContext: SimpleSecurityContext

    private fun path(pattern: String) = PathActionMatcherFactory.INSTANCE.create(pattern.asConfiguration())

    /** A condition that never matches when the scenario is `guard-miss`. */
    private val guard: ConditionMatcher get() = if (scenario == "guard-miss") never else AllConditionMatcher.INSTANCE
    private val never = AllConditionMatcher(mapOf(CONDITION_MATCHER_NEGATE_KEY to true).asConfiguration())

    private fun policy(id: String, type: PolicyType, prefix: String, statementCount: Int): Policy {
        val statements = (1..statementCount).map {
            StatementData(name = "s$it", effect = Effect.ALLOW, action = path("/$prefix/resource$it/**"))
        } + StatementData(name = "deny", effect = Effect.DENY, action = path("/$prefix/admin/**"))
        return PolicyData(id, "category", id, "", type, "tenant", condition = guard, statements = statements)
    }

    /** [source] without the `-mixed` suffix: where the policies come from. */
    private val base: String get() = source.removeSuffix(MIXED_SUFFIX)

    private fun <T : Any> answer(value: T): Mono<T> =
        if (base.endsWith("-deferred")) Mono.defer { Mono.just(value) } else Mono.just(value)

    @Setup
    fun setup() {
        val globalPolicies = listOf(
            policy("global-public", PolicyType.GLOBAL, "public", 10),
            policy("global-docs", PolicyType.GLOBAL, "docs", 10),
            policy("global-health", PolicyType.GLOBAL, "health", 5),
        ) + (3 until this.globalPolicies).map { policy("global-filler$it", PolicyType.GLOBAL, "filler$it", 5) }
        val principalPolicies = listOf(
            policy("principal-a", PolicyType.CUSTOM, "reports", 10),
            policy("principal-b", PolicyType.CUSTOM, "exports", 10),
        )
        val heavy = scenario == "role-heavy"
        val permissionCount = if (heavy) 120 else 50
        val permissions = (1..permissionCount).map {
            PermissionData(id = "p$it", name = "p$it", action = path("/orders/op$it/**"))
        } + PermissionData(id = "deny-internal", name = "deny", effect = Effect.DENY, action = path("/orders/internal/**"))
        val appPermission = AppPermissionData(
            id = "app",
            condition = guard,
            groups = permissions.chunked(10).mapIndexed { index, group -> PermissionGroupData("g$index", permissions = group) },
        )
        val rolePermissions: Map<RoleId, Set<String>> = if (heavy) {
            (1..12).associate { role -> "role$role" to (1..30).map { "p${(role * 7 + it) % permissionCount + 1}" }.toSet() } +
                ("admin" to setOf(AppRolePermission.ALL_PERMISSION_ID))
        } else {
            mapOf(
                "admin" to setOf(AppRolePermission.ALL_PERMISSION_ID),
                "viewer" to (1..20).map { "p$it" }.toSet(),
            )
        }
        val policyRepository = object : PolicyRepository {
            private val global = answer(globalPolicies)
            private val principal = answer(principalPolicies)
            override fun getGlobalPolicy(): Mono<List<Policy>> = global
            override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> = principal
            override fun setPolicy(policy: Policy): Mono<Void> = Mono.empty()
        }
        val appRolePermissionRepository = object : AppRolePermissionRepository {
            override fun getAppRolePermission(
                appId: String,
                spaceId: String,
                roleIds: Set<RoleId>
            ): Mono<AppRolePermission> = Mono.fromCallable {
                AppRolePermissionData(
                    appPermission,
                    roleIds.mapNotNull { roleId -> rolePermissions[roleId]?.let { RolePermissionData(roleId, it) } },
                )
            }
        }
        val snapshot = base == "snapshot"
        if (snapshot) {
            snapshotSource = snapshotSource(globalPolicies + principalPolicies, appPermission, rolePermissions)
        }
        val repositoryEngine = EngineAuthorization(policyRepository, appRolePermissionRepository)
        authorization = when (base) {
            "legacy", "legacy-deferred" -> SimpleAuthorization(policyRepository, appRolePermissionRepository)
            "engine", "engine-deferred" -> repositoryEngine
            "snapshot" -> SnapshotEngineAuthorization(snapshotSource!!)
            else -> error("Unknown source [$source].")
        }
        traced = when {
            !source.endsWith(MIXED_SUFFIX) -> authorization
            snapshot -> SnapshotEngineAuthorization(snapshotSource!!, trace = TraceLevel.FULL)
            else -> EngineAuthorization(
                policyRepository,
                appRolePermissionRepository,
                BlacklistChecker.NoOp,
                RootPrincipalMatcher.NONE,
                TraceLevel.FULL,
            )
        }
        val principal = SimplePrincipal(
            id = "user",
            policies = setOf("principal-a", "principal-b"),
            roles = if (heavy) rolePermissions.keys else setOf("viewer", "admin"),
        )
        securityContext = SimpleSecurityContext(principal)
        request = EvaluateRequest(
            path = when (scenario) {
                "global" -> "/public/resource3/page"
                "role" -> "/orders/op49/detail"
                "global-deny" -> "/public/admin/settings"
                "guard-miss" -> "/public/resource3/page"
                else -> "/unknown/path"
            },
            // The repositories ignore it; the snapshot reads the app's document by it.
            headers = mapOf(AppIdCapable.APP_ID_KEY to "app"),
        )
        // Every source must decide the scenario as the engine over the repositories does, by the same rule, or the
        // numbers compare different work.
        val expected = decisionOf(repositoryEngine, principal)
        listOf(authorization, traced).forEach {
            val actual = decisionOf(it as DecisionAuthorization, principal)
            check(actual == expected) { "Source [$source] decided [$actual] in scenario [$scenario], the engine [$expected]." }
        }
    }

    /** The result and what decided it, by id: the sources decode their own copies of the policies. */
    private fun decisionOf(authorization: DecisionAuthorization, principal: SimplePrincipal): List<Any?> {
        val decision = checkNotNull(authorization.decide(request, SimpleSecurityContext(principal)).block())
        val by = when (val context = decision.verifyContext) {
            is PolicyVerifyContext -> listOf(context.source, context.policy.id, context.statementIndex, context.statement.name)
            is RoleVerifyContext -> listOf(context.roleId, context.permission.id)
            else -> listOf(context?.javaClass)
        }
        return listOf(decision.result) + by
    }

    /** The same data as snapshot documents, loaded before the measurement starts. */
    private fun snapshotSource(
        policies: List<Policy>,
        appPermission: AppPermissionData,
        rolePermissions: Map<RoleId, Set<String>>,
    ): SnapshotSource<CompiledPolicies, CompiledApp> {
        fun document(body: String) =
            SnapshotDocument(SnapshotDocument.CURRENT_SCHEMA, 1, Instant.EPOCH, "{$body}".encodeToByteArray())
        val store = InMemorySnapshotDocumentStore()
        val policiesJson = policies.joinToString(",") { mapper.writeValueAsString(it) }
        store.put(SnapshotKey.Policies, document(""""policies":[$policiesJson]"""))
        val grants = rolePermissions.entries.joinToString(",") { (roleId, permissions) ->
            "\"$roleId\":${mapper.writeValueAsString(permissions)}"
        }
        store.put(
            SnapshotKey.App("app"),
            document(""""appPermission":${mapper.writeValueAsString(appPermission)},"grants":{$grants}"""),
        )
        val compiler = PolicySnapshotCompiler(mapper)
        val source = SnapshotSource(
            store,
            compiler.policies,
            compiler.app,
            SnapshotOptions(revalidateInterval = Duration.ZERO),
        )
        source.load("app").get()
        return source
    }

    @TearDown
    fun tearDown() {
        snapshotSource?.close()
    }

    @Benchmark
    fun authorize(): AuthorizeResult? {
        val authorization = if (++calls and MIXED_TRACE_MASK == 0) traced else authorization
        return authorization.authorize(request, securityContext).block()
    }

    private companion object {
        /**
         * The benchmark jar keeps only one of the `JacksonModule` service files, so `CoSecJsonSerializer` would find no
         * CoSec module there: the modules are registered explicitly.
         */
        val mapper = jsonMapper {
            addModule(kotlinModule())
            addModule(CoSecModule())
        }

        /** Every eighth call is traced in the `-mixed` sources. */
        const val MIXED_TRACE_MASK = 7
        const val MIXED_SUFFIX = "-mixed"
    }
}
