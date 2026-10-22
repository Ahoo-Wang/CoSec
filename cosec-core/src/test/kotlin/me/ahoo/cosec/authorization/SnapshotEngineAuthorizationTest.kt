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

import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.AppIdCapable
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.tenant.Tenant
import me.ahoo.cosec.authorization.engine.CompiledApp
import me.ahoo.cosec.authorization.engine.CompiledPolicies
import me.ahoo.cosec.authorization.engine.LegacyData
import me.ahoo.cosec.authorization.engine.PolicySnapshotCompiler
import me.ahoo.cosec.authorization.engine.appPermissionJson
import me.ahoo.cosec.authorization.engine.permissionJson
import me.ahoo.cosec.authorization.engine.policyJson
import me.ahoo.cosec.authorization.engine.snapshotDocument
import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.snapshot.ManualClock
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.SnapshotOptions
import me.ahoo.cosec.snapshot.SnapshotSource
import me.ahoo.cosec.snapshot.SnapshotStaleException
import me.ahoo.cosec.snapshot.SnapshotUnavailableException
import me.ahoo.cosec.tenant.SimpleTenant
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration

class SnapshotEngineAuthorizationTest {
    private val store = InMemorySnapshotDocumentStore()
    private val sources = ArrayList<SnapshotSource<*, *>>()

    private val data = LegacyData(
        policies = linkedMapOf(
            "g" to policyJson("g", "global", """{"name":"deny","effect":"deny","action":"/deny"}"""),
            "p" to policyJson("p", statements = """{"name":"allow","effect":"allow","action":"/policy"}"""),
        ),
        globalIndex = listOf("g"),
        appPermissions = mapOf("app" to appPermissionJson("app", permissionJson("perm", "/role"))),
        grants = mapOf("app" to mapOf("r1" to """["perm"]""")),
    )

    @AfterEach
    fun close() {
        sources.forEach { it.close() }
    }

    private fun source(
        options: SnapshotOptions = NO_REVALIDATION,
        clock: ManualClock = ManualClock(),
    ): SnapshotSource<CompiledPolicies, CompiledApp> {
        val compiler = PolicySnapshotCompiler()
        val source = SnapshotSource(store, compiler.policies, compiler.app, options, clock)
        sources.add(source)
        return source
    }

    private fun request(path: String) = EvaluateRequest(path = path, headers = mapOf(AppIdCapable.APP_ID_KEY to "app"))

    private fun context(tenant: Tenant = SimpleTenant.DEFAULT) =
        SimpleSecurityContext(SimplePrincipal("user", setOf("p"), setOf("r1")), tenant = tenant)

    @Test
    fun decidesEachLayerFromTheSnapshot() {
        data.publish(store)
        val authorization = SnapshotEngineAuthorization(source())
        mapOf(
            "/deny" to AuthorizeResult.EXPLICIT_DENY,
            "/policy" to AuthorizeResult.ALLOW,
            "/role" to AuthorizeResult.ALLOW,
            "/none" to AuthorizeResult.IMPLICIT_DENY,
        ).forEach { (path, result) ->
            val context = context()
            authorization.decide(request(path), context).block()!!.result.assert().describedAs(path).isEqualTo(result)
        }
        val context = context()
        val decision = authorization.decide(request("/role"), context).block()!!
        (decision.verifyContext as RoleVerifyContext).permission.id.assert().isEqualTo("perm")
    }

    @Test
    fun anInstalledSnapshotIsEvaluatedOnTheCallersThread() {
        data.publish(store)
        val source = source()
        source.load("app").get()
        var evaluatedOn: Thread? = null
        val authorization =
            SnapshotEngineAuthorization(source, onEvaluated = { _, _ -> evaluatedOn = Thread.currentThread() })
        authorization.decide(request("/policy"), context()).block()!!.result.assert().isEqualTo(AuthorizeResult.ALLOW)
        evaluatedOn.assert().isSameAs(Thread.currentThread())
    }

    @Test
    fun aColdLoadContinuesOnBoundedElastic() {
        data.publish(store)
        var evaluatedOn: Thread? = null
        val authorization =
            SnapshotEngineAuthorization(source(), onEvaluated = { _, _ -> evaluatedOn = Thread.currentThread() })
        authorization.decide(request("/policy"), context()).block()!!.result.assert().isEqualTo(AuthorizeResult.ALLOW)
        evaluatedOn!!.name.assert().startsWith("boundedElastic")
    }

    @Test
    fun blacklistAndRootReadNoSnapshot() {
        val source = source()
        val blocked = SnapshotEngineAuthorization(
            source,
            blacklistChecker = object : BlacklistChecker {
                override fun check(request: Request, context: SecurityContext): Mono<Boolean> = Mono.just(false)
            },
        )
        blocked.decide(request("/policy"), context()).block()!!.assert().isSameAs(
            me.ahoo.cosec.authorization.engine.Decisions.BLACKLIST
        )
        val root = SnapshotEngineAuthorization(source, rootPrincipalMatcher = { true })
        root.decide(request("/policy"), context()).block()!!.result.assert().isEqualTo(AuthorizeResult.ALLOW)
        source.entries().assert().isEmpty()
    }

    @Test
    fun anUnusableDocumentFailsTheRequest() {
        store.put(SnapshotKey.Policies, snapshotDocument(1, """"policies":[]""", schema = 99))
        StepVerifier.create(SnapshotEngineAuthorization(source()).decide(request("/policy"), context()))
            .expectError(SnapshotUnavailableException::class.java)
            .verify()
    }

    @Test
    fun aStaleSnapshotFailsTheTenantsThatFailClosed() {
        data.publish(store)
        val clock = ManualClock()
        val options = SnapshotOptions(
            revalidateInterval = Duration.ZERO,
            failClosedTenants = setOf("strict"),
            failClosedMaxLag = Duration.ofMinutes(1),
        )
        val source = source(options, clock)
        source.load("app").get()
        clock.advance(Duration.ofMinutes(2))
        val authorization = SnapshotEngineAuthorization(source)
        StepVerifier.create(authorization.decide(request("/policy"), context(SimpleTenant("strict"))))
            .expectError(SnapshotStaleException::class.java)
            .verify()
        authorization.decide(request("/policy"), context()).block()!!.result.assert().isEqualTo(AuthorizeResult.ALLOW)
    }

    @Test
    fun tracesTheEvaluation() {
        data.publish(store)
        var traced = false
        val authorization = SnapshotEngineAuthorization(
            source(),
            trace = TraceLevel.FULL,
            onEvaluated = { _, evaluation -> traced = evaluation.trace != null },
        )
        authorization.decide(request("/none"), context()).block()
        traced.assert().isTrue()
    }

    private companion object {
        val NO_REVALIDATION = SnapshotOptions(revalidateInterval = Duration.ZERO)
    }
}
