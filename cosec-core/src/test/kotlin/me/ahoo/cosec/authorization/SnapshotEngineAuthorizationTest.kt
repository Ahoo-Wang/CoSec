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

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.AppIdCapable
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.tenant.Tenant
import me.ahoo.cosec.authorization.engine.CompiledApp
import me.ahoo.cosec.authorization.engine.CompiledPolicies
import me.ahoo.cosec.authorization.engine.PolicySnapshotCompiler
import me.ahoo.cosec.blacklist.BlacklistChecker
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.engine.LayerInput
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.parity.LegacyData
import me.ahoo.cosec.parity.appPermissionJson
import me.ahoo.cosec.parity.permissionJson
import me.ahoo.cosec.parity.policyJson
import me.ahoo.cosec.parity.snapshotDocument
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.snapshot.DocumentState
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

    /** Blocks on a request that fails, as callers do, many times; returns the errors they received. */
    private fun failures(authorization: DecisionAuthorization): List<Throwable> = List(BLOCKS) {
        val error = runCatching { authorization.decide(request("/x"), context()).block() }.exceptionOrNull()
        error.assert().isInstanceOf(SnapshotUnavailableException::class.java)
        checkNotNull(error!!.cause)
    }

    @Test
    fun aBrokenEntrysSharedErrorCollectsNothingFromItsCallers() {
        val broken = policyJson("g", "global", condition = """{"noSuchMatcher":{}}""")
        store.put(SnapshotKey.Policies, snapshotDocument(1, """"policies":[$broken]"""))
        val source = source()
        val causes = failures(SnapshotEngineAuthorization(source))
        val compiled = (source.peek()!!.policies as DocumentState.Present).compiled
        val shared = (compiled.globalLayer as LayerInput.Unavailable).error
        // Every caller got its own error, caused by the one the document holds, which stays untouched.
        causes.forEach { it.assert().isSameAs(shared) }
        shared.suppressed.assert().isEmpty()
    }

    @Test
    fun aFailedDocumentsSharedErrorCollectsNothingFromItsCallers() {
        store.put(SnapshotKey.Policies, snapshotDocument(1, """"policies":[]""", schema = 99))
        val source = source()
        failures(SnapshotEngineAuthorization(source))
        val shared = (source.peek()!!.policies as DocumentState.Failed).error
        shared.suppressed.assert().isEmpty()
    }

    /**
     * No role applies in the request's space, so the app is never read, on either path: a request whose app cannot
     * be read is decided all the same (M13).
     */
    @Test
    fun theAppIsNotReadWhenNoRoleAppliesInTheSpace() {
        data.publish(store)
        val principal = SimplePrincipal("user", emptySet(), setOf("r1@s1"))
        val authorizations = listOf(
            EngineAuthorization(data.policyRepository, data.appRolePermissionRepository),
            SnapshotEngineAuthorization(source()),
        )
        authorizations.forEach { authorization ->
            val request = mockk<Request>(relaxed = true) {
                every { path } returns "/x"
                every { spaceId } returns ""
                every { appId } throws IllegalStateException("app")
            }
            authorization.decide(request, SimpleSecurityContext(principal)).block()!!.result
                .assert().isEqualTo(AuthorizeResult.IMPLICIT_DENY)
            verify(exactly = 0) { request.appId }
        }
    }

    private companion object {
        const val BLOCKS = 100
        val NO_REVALIDATION = SnapshotOptions(revalidateInterval = Duration.ZERO)
    }
}
