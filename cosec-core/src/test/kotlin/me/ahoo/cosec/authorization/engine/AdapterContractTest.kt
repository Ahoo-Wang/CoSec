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

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.configuration.Configuration
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.AppIdCapable
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.context.request.SpaceIdCapable
import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.permission.MissingPermissionException
import me.ahoo.cosec.api.permission.PermissionGroup
import me.ahoo.cosec.api.policy.ActionMatcher
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.policy.Statement
import me.ahoo.cosec.api.policy.VerifyResult
import me.ahoo.cosec.api.principal.RoleId
import me.ahoo.cosec.authorization.AppRolePermissionRepository
import me.ahoo.cosec.authorization.AuthorizationImpl
import me.ahoo.cosec.authorization.EngineAuthorization
import me.ahoo.cosec.authorization.MissingPolicyException
import me.ahoo.cosec.authorization.PolicyRepository
import me.ahoo.cosec.authorization.PolicyVerifyContext
import me.ahoo.cosec.authorization.RoleVerifyContext
import me.ahoo.cosec.configuration.JsonConfiguration
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.engine.AbsentReason
import me.ahoo.cosec.engine.CoSecEngine
import me.ahoo.cosec.engine.LayerInput
import me.ahoo.cosec.engine.PolicySet
import me.ahoo.cosec.engine.Rule
import me.ahoo.cosec.engine.RuleEntry
import me.ahoo.cosec.engine.RuleGroup
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.engine.UnresolvedGrant
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Decision
import me.ahoo.cosec.model.LayerKind
import me.ahoo.cosec.permission.AppPermissionData
import me.ahoo.cosec.permission.AppRolePermissionData
import me.ahoo.cosec.permission.PermissionData
import me.ahoo.cosec.permission.PermissionGroupData
import me.ahoo.cosec.permission.RolePermissionData
import me.ahoo.cosec.policy.BlockingAware
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.policy.StatementData
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.cosec.policy.action.PathActionMatcherFactory
import me.ahoo.cosec.policy.condition.AllConditionMatcher
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.kotlin.test.test
import java.net.URI
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

/**
 * The adapter guards the engine cannot check itself (RFC 0002 PR-3, review round 2): every layer is present,
 * groups visit every entry the same way in both passes and report stopping truthfully, role entries carry their role
 * and permission id, errors reach the caller as error signals, and blocking matchers leave the caller's thread.
 */
class AdapterContractTest {
    private class Entry(val entry: RuleEntry, val grantedVia: String?)

    private fun RuleGroup.entries(stopAt: Int = -1): Pair<List<Entry>, Boolean> {
        val entries = mutableListOf<Entry>()
        val stopped = forEachRule { entry, grantedVia ->
            entries += Entry(entry, grantedVia)
            entries.size == stopAt
        }
        return entries to stopped
    }

    private fun Entry.describe(): String = when (val entry = entry) {
        is Rule -> "$grantedVia:${entry.id}:${entry.index}:${entry.name}:${entry.effect}"
        is UnresolvedGrant -> "$grantedVia:unresolved:${entry.permissionId}"
    }

    private fun path(pattern: String) = PathActionMatcherFactory.INSTANCE.create(pattern.asConfiguration())

    private fun permission(id: String, effect: Effect = Effect.ALLOW) =
        PermissionData(id = id, name = "name-$id", effect = effect, action = AllActionMatcher.INSTANCE)

    private val catalog = AppPermissionData(
        "app",
        groups = listOf(
            PermissionGroupData(
                "g",
                permissions = listOf(permission("a"), permission("b", Effect.DENY), permission("c"))
            )
        ),
    )

    private fun roleGroup(vararg grants: Pair<String, Set<String>>, appPermission: AppPermission = catalog) =
        AppPermissionGroup(
            if (appPermission is AppPermissionData) appPermission.engineRules() else AppPermissionRules(appPermission),
            grants.map { (role, ids) -> RolePermissionData(role, ids) },
        )

    @Test
    fun roleGroupVisitsEveryGrantInOrderWithItsRoleAndPermissionId() {
        val group = roleGroup("r1" to linkedSetOf("c", "a"), "r2" to setOf("*"), "r3" to linkedSetOf("b"))
        val (entries, stopped) = group.entries()
        stopped.assert().isFalse()
        entries.map { it.describe() }.assert().containsExactly(
            "r1:c:0:name-c:ALLOW",
            "r1:a:1:name-a:ALLOW",
            "r2:a:0:name-a:ALLOW",
            "r2:b:1:name-b:DENY",
            "r2:c:2:name-c:ALLOW",
            // The 7.0 trap: a wildcard grant ends its role's grants, never the layer.
            "r3:b:0:name-b:DENY",
        )
        // Both passes see the same entries; cached rules are the same objects.
        val (again, _) = group.entries()
        again.map { it.entry }.assert().containsExactlyElementsOf(entries.map { it.entry })
        group.guard.assert().isSameAs(roleGroup("r1" to setOf("a")).guard)
    }

    @Test
    fun forEachRuleReturnsTrueExactlyWhenTheVisitorStops() {
        val group = roleGroup("r1" to setOf("*"), "r2" to linkedSetOf("a", "c"))
        for (stopAt in 1..5) {
            val (entries, stopped) = group.entries(stopAt)
            stopped.assert().isTrue()
            entries.assert().hasSize(stopAt)
        }
        group.entries(stopAt = 6).second.assert().isFalse()
        val policy = PolicyGroup(policy("p", Effect.ALLOW, Effect.DENY))
        policy.entries(stopAt = 1).let { (entries, stopped) ->
            stopped.assert().isTrue()
            entries.assert().hasSize(1)
        }
        policy.entries(stopAt = 3).second.assert().isFalse()
    }

    @Test
    fun forEachRuleNeverSwallowsExceptions() {
        val group = roleGroup("r1" to setOf("a"))
        assertThrows<IllegalStateException> { group.forEachRule { _, _ -> throw IllegalStateException("engine") } }
        val policy = PolicyGroup(policy("p", Effect.ALLOW))
        assertThrows<IllegalStateException> { policy.forEachRule { _, _ -> throw IllegalStateException("engine") } }
    }

    @Test
    fun undefinedGrantIsAnUnresolvedEntryWhereTheGrantIs() {
        val group = roleGroup("r1" to linkedSetOf("a", "ghost", "c", "phantom"))
        val (entries, _) = group.entries()
        entries.map { it.describe() }.assert().containsExactly(
            "r1:a:0:name-a:ALLOW",
            "r1:unresolved:ghost",
            "r1:c:2:name-c:ALLOW",
            "r1:unresolved:phantom",
        )
        val error = (entries[1].entry as UnresolvedGrant).error as MissingPermissionException
        // The exception 7.0 throws: every id of the role the app does not define.
        error.appId.assert().isEqualTo("app")
        error.roleId.assert().isEqualTo("r1")
        error.permissionIds.assert().containsExactly("ghost", "phantom")
        // Built once: both passes see the same entry and exception.
        group.entries().first[1].entry.assert().isSameAs(entries[1].entry)
    }

    @Test
    fun policyEntriesCarryNeitherRoleNorPermissionId() {
        val (entries, _) = PolicyGroup(policy("p", Effect.ALLOW, Effect.DENY)).entries()
        entries.map { it.describe() }.assert().containsExactly("null:null:0:s0:ALLOW", "null:null:1:s1:DENY")
    }

    @Test
    fun appPermissionsOtherThanTheDataClassAreAdaptedPerRequest() {
        val custom = object : AppPermission {
            override val id: String = "custom"
            override val condition: ConditionMatcher = catalog.condition
            override val groups: List<PermissionGroup> = catalog.groups
        }
        val (entries, _) = roleGroup("r1" to setOf("*"), appPermission = custom).entries()
        entries.map { it.describe() }.assert().containsExactly(
            "r1:a:0:name-a:ALLOW",
            "r1:b:1:name-b:DENY",
            "r1:c:2:name-c:ALLOW",
        )
    }

    @Test
    fun grantPositionsBeyondTheCacheStillCarryTheirIndex() {
        val permissions = (0 until 70).map { permission("p$it") }
        val app = AppPermissionData("big", groups = listOf(PermissionGroupData("g", permissions = permissions)))
        val ids = permissions.map { it.id }.toCollection(LinkedHashSet())
        val group = roleGroup("r" to ids, appPermission = app)
        val (entries, _) = group.entries()
        entries.map { (it.entry as Rule).index }.assert().containsExactlyElementsOf(0 until 70)
        // The trace aligns the two passes by identity: the second sees the very same rules.
        group.entries().first.map { it.entry }.zip(entries.map { it.entry }).assert().allMatch { (a, b) -> a === b }
    }

    private fun policy(id: String, vararg effects: Effect): Policy = PolicyData(
        id = id,
        category = "",
        name = id,
        description = "",
        type = PolicyType.GLOBAL,
        tenantId = "",
        statements = effects.mapIndexed { index, effect ->
            StatementData(name = "s$index", effect = effect, action = AllActionMatcher.INSTANCE)
        },
    )

    private fun repositories(
        global: Mono<List<Policy>> = Mono.empty(),
        principal: Mono<List<Policy>> = Mono.empty(),
        role: Mono<AppRolePermission> = Mono.empty()
    ) = PolicySetLoader(
        object : PolicyRepository {
            override fun getGlobalPolicy(): Mono<List<Policy>> = global
            override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> = principal
            override fun setPolicy(policy: Policy): Mono<Void> = Mono.empty()
        },
        object : AppRolePermissionRepository {
            override fun getAppRolePermission(appId: String, spaceId: String, roleIds: Set<RoleId>) = role
        },
    )

    private fun authzRequest(
        policies: Set<String> = emptySet(),
        roles: Set<String> = emptySet(),
        spaceId: String = ""
    ): AuthzRequest = LegacyFacts.authzRequest(
        EvaluateRequest(
            headers = mapOf(AppIdCapable.APP_ID_KEY to "app", SpaceIdCapable.SPACE_ID_KEY to spaceId)
                .filterValues { it.isNotEmpty() },
        ),
        SimpleSecurityContext(SimplePrincipal("user", policies = policies, roles = roles)),
    )

    private fun PolicySetLoader.layers(request: AuthzRequest): List<LayerInput> =
        load(request).block()!!.layers.also { layers ->
            layers.map { it.kind }.assert().containsExactly(LayerKind.GLOBAL, LayerKind.PRINCIPAL, LayerKind.ROLE)
        }

    @Test
    fun everyLayerIsPresentEvenWhenEmpty() {
        val layers = repositories().layers(authzRequest())
        layers.assert().containsExactly(
            LayerInput.Absent(LayerKind.GLOBAL, AbsentReason.NO_POLICIES),
            LayerInput.Absent(LayerKind.PRINCIPAL, AbsentReason.NO_POLICIES),
            LayerInput.Absent(LayerKind.ROLE, AbsentReason.NO_ROLES),
        )
        repositories().layers(authzRequest(roles = setOf("r@s1")))[2].assert()
            .isEqualTo(LayerInput.Absent(LayerKind.ROLE, AbsentReason.NO_ROLE_IN_SPACE))
        repositories().layers(authzRequest(roles = setOf("r@s1"), spaceId = "s1"))[2].assert()
            .isEqualTo(LayerInput.Absent(LayerKind.ROLE, AbsentReason.NO_APP_PERMISSION))
    }

    @Test
    fun failedReadsAreUnavailableLayersNotErrorSignals() {
        val down = IllegalStateException("down")
        val loader = PolicySetLoader(
            object : PolicyRepository {
                override fun getGlobalPolicy(): Mono<List<Policy>> = Mono.error(down)
                override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> = throw down
                override fun setPolicy(policy: Policy): Mono<Void> = Mono.empty()
            },
            object : AppRolePermissionRepository {
                override fun getAppRolePermission(appId: String, spaceId: String, roleIds: Set<RoleId>) =
                    Mono.error<AppRolePermission>(down)
            },
        )
        loader.layers(authzRequest(policies = setOf("p"), roles = setOf("r"))).assert().containsExactly(
            LayerInput.Unavailable(LayerKind.GLOBAL, down),
            LayerInput.Unavailable(LayerKind.PRINCIPAL, down),
            LayerInput.Unavailable(LayerKind.ROLE, down),
        )
    }

    @Test
    fun missingPrincipalPoliciesAreReportedOnTheLoadedLayer() {
        val loader = repositories(principal = Mono.just(listOf(policy("p"))))
        val principal = loader.layers(authzRequest(policies = setOf("p", "q", "r")))[1] as LayerInput.Loaded
        principal.groups.assert().isEmpty()
        val missing = checkNotNull(principal.missing)
        missing.policyIds.assert().containsExactly("q", "r")
        (missing.error as MissingPolicyException).policyIds.assert().containsExactly("q", "r")
        // An empty read means none was found.
        val empty = repositories().layers(authzRequest(policies = setOf("p")))[1] as LayerInput.Loaded
        checkNotNull(empty.missing).policyIds.assert().containsExactly("p")
    }

    @Test
    fun aLayerIsASnapshotOfTheRepositoryList() {
        val g1 = policy("g1", Effect.ALLOW) as PolicyData
        val policies = mutableListOf(g1, policy("g2"))
        val global = repositories(global = Mono.just(policies)).layers(authzRequest())[0] as LayerInput.Loaded
        // A list the repository changes later can never pair one policy's guard with another's rules.
        policies.add(0, policy("g0", Effect.DENY))
        policies.removeAt(2)
        global.groups.map { it.id }.assert().containsExactly("g1", "g2")
        // PolicyData adapts once, whatever the request.
        global.groups[0].assert().isSameAs(g1.engineGroup())
    }

    @Test
    fun blockingMatchersAreFoundInAnyLayer() {
        val blocking = object : ConditionMatcher, BlockingAware {
            override val type: String = "blocking"
            override val configuration: Configuration = JsonConfiguration.newPojoConfiguration()
            override val requiresBlocking: Boolean = true
            override fun match(request: Request, securityContext: SecurityContext): Boolean = true
        }
        val blockingPolicy = PolicyData("b", "", "b", "", PolicyType.GLOBAL, "", condition = blocking)
        val blockingApp = AppRolePermissionData(AppPermissionData("app", condition = blocking), emptyList())
        fun requiresBlocking(
            loader: PolicySetLoader,
            request: AuthzRequest = authzRequest(policies = setOf("b"), roles = setOf("r"))
        ) =
            PolicySetLoader.requiresBlocking(loader.load(request).block()!!)

        requiresBlocking(repositories(global = Mono.just(listOf(policy("g"))))).assert().isFalse()
        requiresBlocking(repositories(global = Mono.just(listOf(blockingPolicy)))).assert().isTrue()
        requiresBlocking(repositories(principal = Mono.just(listOf(blockingPolicy)))).assert().isTrue()
        requiresBlocking(repositories(role = Mono.just(blockingApp))).assert().isTrue()
        // A layer that fails before its groups run never moves the evaluation.
        requiresBlocking(
            repositories(principal = Mono.just(listOf(blockingPolicy))),
            authzRequest(policies = setOf("b", "x"))
        )
            .assert().isFalse()
        PolicySetLoader.requiresBlocking(PolicySet.NOT_LOADED).assert().isFalse()
    }

    /** A matcher that throws an Error: it propagates out of the engine and becomes an error signal, as in 7.0. */
    @ParameterizedTest
    @EnumSource(AuthorizationImpl::class)
    fun errorsBecomeErrorSignals(impl: AuthorizationImpl) {
        val failing = object : ConditionMatcher {
            override val type: String = "failing"
            override val configuration: Configuration = JsonConfiguration.newPojoConfiguration()
            override fun match(request: Request, securityContext: SecurityContext): Boolean = TODO("not implemented")
        }
        val policy = PolicyData("p", "", "p", "", PolicyType.GLOBAL, "", condition = failing)
        val authorization = impl.create(
            object : PolicyRepository {
                override fun getGlobalPolicy(): Mono<List<Policy>> = Mono.just(listOf(policy))
                override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> = Mono.empty()
                override fun setPolicy(policy: Policy): Mono<Void> = Mono.empty()
            },
            object : AppRolePermissionRepository {
                override fun getAppRolePermission(appId: String, spaceId: String, roleIds: Set<RoleId>) =
                    Mono.empty<AppRolePermission>()
            },
        )
        // Assembling the Mono must not throw: the evaluation runs on subscription.
        val decision = authorization.decide(EvaluateRequest(), SimpleSecurityContext(SimplePrincipal("user")))
        decision.test().expectError(NotImplementedError::class.java).verify()
    }

    /**
     * Without a blocking matcher the evaluation stays on the caller's thread; with one it runs on boundedElastic,
     * never on the thread that subscribed (an event loop in WebFlux and Gateway).
     */
    @Test
    fun blockingMatchersNeverRunOnTheCallersThread() {
        val threads = Collections.synchronizedList(mutableListOf<String>())
        fun recording(blocks: Boolean): ConditionMatcher = if (blocks) {
            object : ConditionMatcher, BlockingAware {
                override val type: String = "recording"
                override val configuration: Configuration = JsonConfiguration.newPojoConfiguration()
                override val requiresBlocking: Boolean = true
                override fun match(request: Request, securityContext: SecurityContext): Boolean {
                    threads += Thread.currentThread().name
                    return true
                }
            }
        } else {
            object : ConditionMatcher {
                override val type: String = "recording"
                override val configuration: Configuration = JsonConfiguration.newPojoConfiguration()
                override fun match(request: Request, securityContext: SecurityContext): Boolean {
                    threads += Thread.currentThread().name
                    return true
                }
            }
        }
        val loop = Schedulers.newSingle("event-loop")
        try {
            for (blocks in listOf(false, true)) {
                threads.clear()
                // A blocking matcher in the role layer only: the whole evaluation moves (X2).
                val policy = PolicyData(
                    "p",
                    "",
                    "p",
                    "",
                    PolicyType.GLOBAL,
                    "",
                    condition = recording(blocks = false),
                    statements = listOf(StatementData(action = AllActionMatcher.INSTANCE)),
                )
                val app = AppRolePermissionData(
                    AppPermissionData("app", condition = recording(blocks)),
                    listOf(RolePermissionData("r", setOf("*"))),
                )
                val authorization = EngineAuthorization(
                    object : PolicyRepository {
                        override fun getGlobalPolicy(): Mono<List<Policy>> = Mono.just(listOf(policy))
                        override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> = Mono.empty()
                        override fun setPolicy(policy: Policy): Mono<Void> = Mono.empty()
                    },
                    object : AppRolePermissionRepository {
                        override fun getAppRolePermission(appId: String, spaceId: String, roleIds: Set<RoleId>) =
                            Mono.just<AppRolePermission>(app)
                    },
                )
                val context = SimpleSecurityContext(SimplePrincipal("user", roles = setOf("r")))
                authorization.authorize(EvaluateRequest(headers = mapOf(AppIdCapable.APP_ID_KEY to "app")), context)
                    .subscribeOn(loop)
                    .block()!!.authorized.assert().isTrue()
                threads.assert().hasSize(2)
                if (blocks) {
                    threads.assert().allMatch { it.startsWith("boundedElastic") }
                } else {
                    threads.assert().allMatch { it.startsWith("event-loop") }
                }
            }
        } finally {
            loop.dispose()
        }
    }

    /** X5: a statement that overrides verify is verified through it, so both implementations agree. */
    @ParameterizedTest
    @EnumSource(AuthorizationImpl::class)
    fun customStatementsAreVerifiedThroughTheirOverride(impl: AuthorizationImpl) {
        val custom = object : Statement {
            override val name: String = "custom"
            override val effect: Effect = Effect.DENY
            override val action: ActionMatcher = AllActionMatcher.INSTANCE
            override val condition: ConditionMatcher = AllConditionMatcher.INSTANCE
            override fun verify(request: Request, securityContext: SecurityContext): VerifyResult =
                if (request.path == "/denied") VerifyResult.EXPLICIT_DENY else VerifyResult.IMPLICIT_DENY
        }
        val policy = PolicyData(
            "p",
            "",
            "p",
            "",
            PolicyType.GLOBAL,
            "",
            statements = listOf(custom, StatementData(name = "all", action = AllActionMatcher.INSTANCE)),
        )
        val authorization = impl.create(
            object : PolicyRepository {
                override fun getGlobalPolicy(): Mono<List<Policy>> = Mono.just(listOf(policy))
                override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> = Mono.empty()
                override fun setPolicy(policy: Policy): Mono<Void> = Mono.empty()
            },
            object : AppRolePermissionRepository {
                override fun getAppRolePermission(appId: String, spaceId: String, roleIds: Set<RoleId>) =
                    Mono.empty<AppRolePermission>()
            },
        )
        val context = SimpleSecurityContext(SimplePrincipal("user"))
        val denied = authorization.decide(EvaluateRequest(path = "/denied"), context).block()!!
        denied.result.assert().isEqualTo(AuthorizeResult.EXPLICIT_DENY)
        (denied.verifyContext as PolicyVerifyContext).statement.assert().isSameAs(custom)
        authorization.decide(EvaluateRequest(path = "/other"), context).block()!!.result.assert()
            .isEqualTo(AuthorizeResult.ALLOW)
    }

    /**
     * The app and space are read where 7.0 reads them: in the role layer, after the principal's roles. A request whose
     * query cannot be read (its header is missing, so the read falls back to the query) is never read for a
     * principal without roles, fails closed when the role layer is reached, and does not fail when an earlier layer
     * already denied, as in 7.0.
     */
    @ParameterizedTest
    @EnumSource(AuthorizationImpl::class)
    fun aRequestWhoseQueryCannotBeReadFailsClosed(impl: AuthorizationImpl) {
        val queries = mutableListOf<String>()

        // The interface's own appId, spaceId and deviceId: header first, then the query.
        fun request(path: String) = object : Request {
            override val path: String = path
            override val method: String = "POST"
            override val remoteIp: String = "10.0.0.1"
            override val origin: URI = URI.create("http://origin")
            override val referer: URI = URI.create("http://referer")
            override val attributes: Map<String, String> = emptyMap()
            override fun withAttributes(attributes: Map<String, String>): Request = this
            override fun getHeader(key: String): String = ""
            override fun getCookieValue(name: String): String = ""
            override fun getQuery(key: String): String {
                queries += key
                throw IllegalStateException("query unreadable")
            }
        }
        val global = PolicyData(
            "global",
            "",
            "global",
            "",
            PolicyType.GLOBAL,
            "",
            statements = listOf(
                StatementData(name = "deny", effect = Effect.DENY, action = path("/denied/**")),
                StatementData(name = "allow", action = AllActionMatcher.INSTANCE),
            ),
        )
        val authorization = impl.create(
            object : PolicyRepository {
                override fun getGlobalPolicy(): Mono<List<Policy>> = Mono.just(listOf(global))
                override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> = Mono.empty()
                override fun setPolicy(policy: Policy): Mono<Void> = Mono.empty()
            },
            object : AppRolePermissionRepository {
                override fun getAppRolePermission(appId: String, spaceId: String, roleIds: Set<RoleId>) =
                    Mono.empty<AppRolePermission>()
            },
        )
        val withoutRoles = SimpleSecurityContext(SimplePrincipal("user"))
        val withRoles = SimpleSecurityContext(SimplePrincipal("user", roles = setOf("r")))

        authorization.authorize(request("/public"), withoutRoles).test()
            .expectNext(AuthorizeResult.ALLOW).verifyComplete()
        queries.assert().isEmpty()
        authorization.authorize(request("/public"), withRoles).test()
            .expectError(IllegalStateException::class.java).verify()
        queries.assert().isNotEmpty()
        authorization.authorize(request("/denied/x"), withRoles).test()
            .expectNext(AuthorizeResult.EXPLICIT_DENY).verifyComplete()
    }

    /** RFC 0002 §3.3: the engine's request is not read from the request at all in phase 2. */
    @Test
    fun theEnginesRequestReadsNothingFromTheRequest() {
        val request = mockk<Request>()
        val context = mockk<SecurityContext>()
        val authzRequest = LegacyFacts.authzRequest(request, context)
        authzRequest.environment.blocked.assert().isFalse()
        authzRequest.subject.root.assert().isFalse()
        (authzRequest.host as LegacyFacts).request.assert().isSameAs(request)
    }

    @Test
    fun grantPositionsKeepTheirRuleAcrossPassesAndRequests() {
        val permissions = (0 until 70).map { permission("p$it") }
        val app = AppPermissionData("big", groups = listOf(PermissionGroupData("g", permissions = permissions)))
        val ids = permissions.map { it.id }.toCollection(LinkedHashSet())
        val group = roleGroup("r" to ids, appPermission = app)
        val first = group.entries().first.map { it.entry }
        // Both passes of one evaluation see the same objects, beyond the shared cache too.
        group.entries().first.map { it.entry }.zip(first).assert().allMatch { (a, b) -> a === b }
        // Another request shares the cached positions and builds its own tail.
        val other = roleGroup("r" to ids, appPermission = app).entries().first.map { it.entry }
        (0 until PermissionRules.CACHED_POSITIONS).assert().allMatch { other[it] === first[it] }
        (PermissionRules.CACHED_POSITIONS until 70).assert().allMatch { other[it] !== first[it] }
    }

    @Test
    fun aFullTraceOverLongGrantListsFindsNoContractBreach() {
        val permissions = (0 until 70).map { permission("p$it", if (it == 69) Effect.DENY else Effect.ALLOW) }
        val app = AppRolePermissionData(
            AppPermissionData("app", groups = listOf(PermissionGroupData("g", permissions = permissions))),
            listOf(RolePermissionData("r", permissions.map { it.id }.toCollection(LinkedHashSet()))),
        )
        val request = authzRequest(roles = setOf("r"))
        val policies = repositories(role = Mono.just(app)).load(request).block()!!
        for (level in listOf(TraceLevel.FULL, TraceLevel.EXHAUSTIVE)) {
            val evaluation = CoSecEngine.evaluate(request, policies, level)
            (evaluation.decision as Decision.Deny).by.assert().isNotNull()
            val groups = checkNotNull(evaluation.trace).layers.flatMap { it.groups }
            groups.assert().isNotEmpty()
            groups.map { it.contractError }.assert().allMatch { it == null }
            groups.single().rules.assert().hasSize(70)
        }
    }

    /** M25: a permission granted at different positions reports each grant's own position. */
    @Test
    fun aPermissionGrantedAtSeveralPositionsReportsEachIndex() {
        val group =
            roleGroup("r1" to linkedSetOf("b", "a"), "r2" to linkedSetOf("c", "b", "a"), "r3" to linkedSetOf("a"))
        group.entries().first.map { it.describe() }.assert().containsExactly(
            "r1:b:0:name-b:DENY",
            "r1:a:1:name-a:ALLOW",
            "r2:c:0:name-c:ALLOW",
            "r2:b:1:name-b:DENY",
            "r2:a:2:name-a:ALLOW",
            "r3:a:0:name-a:ALLOW",
        )
    }

    /** The principal's policies and roles are read where 7.0 reads them: a read that throws fails only its layer. */
    @Test
    fun aPrincipalWhosePoliciesCannotBeReadFailsOnlyThatLayer() {
        val down = IllegalStateException("principal unreadable")
        val context = mockk<SecurityContext> {
            every { principal.policies } throws down
            every { principal.roles } throws down
        }
        val layers = repositories().load(LegacyFacts.authzRequest(EvaluateRequest(), context)).block()!!.layers
        layers.assert().containsExactly(
            LayerInput.Absent(LayerKind.GLOBAL, AbsentReason.NO_POLICIES),
            LayerInput.Unavailable(LayerKind.PRINCIPAL, down),
            LayerInput.Unavailable(LayerKind.ROLE, down),
        )
    }

    private fun authorization(
        impl: AuthorizationImpl,
        global: Mono<List<Policy>> = Mono.empty(),
        principal: Mono<List<Policy>> = Mono.empty(),
        role: Mono<AppRolePermission> = Mono.empty()
    ) = impl.create(
        object : PolicyRepository {
            override fun getGlobalPolicy(): Mono<List<Policy>> = global
            override fun getPolicies(policyIds: Set<String>): Mono<List<Policy>> = principal
            override fun setPolicy(policy: Policy): Mono<Void> = Mono.empty()
        },
        object : AppRolePermissionRepository {
            override fun getAppRolePermission(appId: String, spaceId: String, roleIds: Set<RoleId>) = role
        },
    )

    /**
     * M-1: two roles both granting more than 64 permissions. Position 64 is a different permission in each role,
     * so a memo keyed by position alone would visit `ra`'s allow where `rb` grants its deny.
     */
    @ParameterizedTest
    @EnumSource(AuthorizationImpl::class)
    fun longGrantListsOfDifferentRolesKeepTheirOwnRules(impl: AuthorizationImpl) {
        val shared = (0 until 64).map { permission("f$it") }
        val permissions = shared + permission("a64") + permission("b64", Effect.DENY)
        val catalog = AppPermissionData("app", groups = listOf(PermissionGroupData("g", permissions = permissions)))
        val custom = object : AppPermission {
            override val id: String = "app"
            override val condition: ConditionMatcher = catalog.condition
            override val groups: List<PermissionGroup> = catalog.groups
        }
        for (appPermission in listOf<AppPermission>(catalog, custom)) {
            val grants = listOf(
                RolePermissionData("ra", (shared.map { it.id } + "a64").toCollection(LinkedHashSet())),
                RolePermissionData("rb", (shared.map { it.id } + "b64").toCollection(LinkedHashSet())),
            )
            val decision = authorization(impl, role = Mono.just(AppRolePermissionData(appPermission, grants)))
                .decide(EvaluateRequest(), SimpleSecurityContext(SimplePrincipal("user", roles = setOf("ra", "rb"))))
                .block()!!
            decision.result.assert().describedAs(
                appPermission.javaClass.simpleName
            ).isEqualTo(AuthorizeResult.EXPLICIT_DENY)
            (decision.verifyContext as RoleVerifyContext).permission.id.assert().isEqualTo("b64")
        }
    }

    /** m-1: concurrent first requests for the same grant positions all get the one shared rule per position. */
    @Test
    fun concurrentRequestsShareOneRulePerPosition() {
        val threads = 8
        val positions = PermissionRules.CACHED_POSITIONS
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(300) {
                val rules = PermissionRules(permission("p"))
                val start = CyclicBarrier(threads)
                val seen = (0 until threads).map { thread ->
                    pool.submit<List<StatementRule>> {
                        start.await()
                        // Every thread races for every position, in its own order.
                        val order = (0 until positions).map { (it * (2 * thread + 1)) % positions }
                        order.map { position -> position to rules.at(position) }.sortedBy { it.first }.map { it.second }
                    }
                }.map { it.get() }
                for (position in 0 until positions) {
                    val first = seen[0][position]
                    seen.assert().allMatch { it[position] === first }
                    rules.at(position).assert().isSameAs(first)
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /** n-2: a repository's ready error is the layer's error itself, not Reactor's wrapper, for an Error too. */
    @ParameterizedTest
    @EnumSource(AuthorizationImpl::class)
    fun aReadyErrorFromARepositoryIsRethrownUnchanged(impl: AuthorizationImpl) {
        val error = AssertionError("store invariant broken")
        val failed = Mono.error<List<Policy>>(error)
        repositories(global = failed).layers(authzRequest())[0].assert()
            .isEqualTo(LayerInput.Unavailable(LayerKind.GLOBAL, error))
        authorization(impl, global = failed)
            .decide(EvaluateRequest(), SimpleSecurityContext(SimplePrincipal("user")))
            .test()
            .expectErrorSatisfies { it.assert().isSameAs(error) }
            .verify()
    }

    /**
     * n-3: a global and a principal policy that are different objects with the same id, statement index and name.
     * The principal's deny decides, and the verify context must name its objects, not the global's.
     */
    @ParameterizedTest
    @EnumSource(AuthorizationImpl::class)
    fun theDecidedPolicyIsFoundByIdentityNotById(impl: AuthorizationImpl) {
        fun policy(effect: Effect) = PolicyData(
            "p",
            "",
            "p",
            "",
            PolicyType.GLOBAL,
            "",
            statements = listOf(StatementData(name = "s", effect = effect, action = AllActionMatcher.INSTANCE)),
        )
        val global = policy(Effect.ALLOW)
        val principal = policy(Effect.DENY)
        val decision = authorization(impl, global = Mono.just(listOf(global)), principal = Mono.just(listOf(principal)))
            .decide(EvaluateRequest(), SimpleSecurityContext(SimplePrincipal("user", policies = setOf("p"))))
            .block()!!
        decision.result.assert().isEqualTo(AuthorizeResult.EXPLICIT_DENY)
        val verifyContext = decision.verifyContext as PolicyVerifyContext
        verifyContext.source.assert().isEqualTo(me.ahoo.cosec.authorization.PolicyVerifySource.PRINCIPAL)
        verifyContext.policy.assert().isSameAs(principal)
        verifyContext.statement.assert().isSameAs(principal.statements[0])
    }
}
