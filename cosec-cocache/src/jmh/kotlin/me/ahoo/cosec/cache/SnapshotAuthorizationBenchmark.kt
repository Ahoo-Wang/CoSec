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

package me.ahoo.cosec.cache

import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.request.AppIdCapable
import me.ahoo.cosec.api.permission.AppRolePermission
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.authorization.EngineAuthorization
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.permission.AppPermissionData
import me.ahoo.cosec.permission.PermissionData
import me.ahoo.cosec.permission.PermissionGroupData
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.policy.StatementData
import me.ahoo.cosec.policy.action.PathActionMatcherFactory
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.serialization.CoSecModule
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule

/**
 * The policy read path that phase 3 replaces (RFC 0003 §9): the Redis repositories over real CoCache engines on the
 * in-memory backend, every read a local (L2) hit, through `toLocalFirstMono`. The repositories in
 * `SimpleAuthorizationBenchmark` answer with `Mono.just` and cannot show this cost.
 *
 * [source] is `repository` only for now: the `snapshot` source reads the same data through the CoCache snapshot
 * store, which RFC 0003 PR-3 adds, together with that value. Only recorded, not gated.
 *
 * Same data as the `global`, `role` and `none` scenarios of `SimpleAuthorizationBenchmark`.
 */
@State(Scope.Benchmark)
open class SnapshotAuthorizationBenchmark {
    @Param("global", "role", "none")
    lateinit var scenario: String

    @Param("repository")
    lateinit var source: String

    // The benchmark jar keeps only one of the JacksonModule service files: the CoSec module is registered explicitly.
    private val mapper = jsonMapper {
        addModule(kotlinModule())
        addModule(CoSecModule())
    }
    private lateinit var runtime: CacheRuntime
    private lateinit var authorization: Authorization
    private lateinit var request: EvaluateRequest
    private lateinit var securityContext: SimpleSecurityContext

    private fun path(pattern: String) = PathActionMatcherFactory.INSTANCE.create(pattern.asConfiguration())

    private fun policy(id: String, type: PolicyType, prefix: String, statementCount: Int): PolicyData {
        val statements = (1..statementCount).map {
            StatementData(name = "s$it", effect = Effect.ALLOW, action = path("/$prefix/resource$it/**"))
        } + StatementData(name = "deny", effect = Effect.DENY, action = path("/$prefix/admin/**"))
        return PolicyData(id, "category", id, "", type, "tenant", statements = statements)
    }

    @Setup
    fun setup() {
        check(source == "repository") { "Unknown source [$source]." }
        runtime = CacheRuntime.builder().backend(InMemoryBackend()).build()
        val policyCache: PolicyCache = runtime.createCache(CoSecCacheSpecs.policy(CoSecCacheSpecs.jacksonCodec(mapper)))
        val globalPolicyIndexCache = runtime.createGlobalPolicyIndex(CoSecCacheSpecs.globalPolicyIndex())
        val appPermissionCache: AppPermissionCache =
            runtime.createCache(CoSecCacheSpecs.appPermission(CoSecCacheSpecs.jacksonCodec(mapper)))
        val rolePermissionCache: RolePermissionCache =
            runtime.createCache(CoSecCacheSpecs.rolePermission(CoSecCacheSpecs.jacksonCodec(mapper)))
        val policyRepository = RedisPolicyRepository(globalPolicyIndexCache, policyCache)
        listOf(
            policy("global-public", PolicyType.GLOBAL, "public", 10),
            policy("global-docs", PolicyType.GLOBAL, "docs", 10),
            policy("global-health", PolicyType.GLOBAL, "health", 5),
            policy("principal-a", PolicyType.CUSTOM, "reports", 10),
            policy("principal-b", PolicyType.CUSTOM, "exports", 10),
        ).forEach { policyRepository.setPolicy(it).block() }
        val permissions = (1..50).map {
            PermissionData(id = "p$it", name = "p$it", action = path("/orders/op$it/**"))
        } + PermissionData(id = "deny-internal", name = "deny", effect = Effect.DENY, action = path("/orders/internal/**"))
        appPermissionCache.put(
            "app",
            AppPermissionData(
                id = "app",
                groups = permissions.chunked(10).mapIndexed { index, group -> PermissionGroupData("g$index", permissions = group) },
            ),
        )
        rolePermissionCache.put(AppSpacedRoleId("app", "admin"), setOf(AppRolePermission.ALL_PERMISSION_ID))
        rolePermissionCache.put(AppSpacedRoleId("app", "viewer"), (1..20).map { "p$it" }.toSet())
        authorization = EngineAuthorization(policyRepository, RedisAppRolePermissionRepository(appPermissionCache, rolePermissionCache))
        securityContext = SimpleSecurityContext(
            SimplePrincipal(id = "user", policies = setOf("principal-a", "principal-b"), roles = setOf("viewer", "admin")),
        )
        request = EvaluateRequest(
            path = when (scenario) {
                "global" -> "/public/resource3/page"
                "role" -> "/orders/op49/detail"
                else -> "/unknown/path"
            },
            headers = mapOf(AppIdCapable.APP_ID_KEY to "app"),
        )
        // Writes evict the local copies: the first read fills them, so every measured read is a local hit.
        authorize()
        // A local hit answers on the caller's thread; a read that reaches the backend continues on boundedElastic.
        check(authorization.authorize(request, securityContext).toFuture().isDone) {
            "The repositories still read the backend: the benchmark would not measure local hits."
        }
    }

    @TearDown
    fun tearDown() {
        runtime.close()
    }

    @Benchmark
    fun authorize(): AuthorizeResult? = authorization.authorize(request, securityContext).block()
}
