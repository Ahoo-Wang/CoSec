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

package me.ahoo.cosec.runtime

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.engine.flat.CoSecFlatEngine
import me.ahoo.cosec.membership.MembershipProvider
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.PipelineFixtures.ANONYMOUS
import me.ahoo.cosec.runtime.PipelineFixtures.EXPLICIT_DENY
import me.ahoo.cosec.runtime.PipelineFixtures.GENERATED_ID
import me.ahoo.cosec.runtime.PipelineFixtures.IMPLICIT_DENY
import me.ahoo.cosec.runtime.PipelineFixtures.InstalledViews
import me.ahoo.cosec.runtime.PipelineFixtures.LoadingViews
import me.ahoo.cosec.runtime.PipelineFixtures.PERMIT
import me.ahoo.cosec.runtime.PipelineFixtures.PRINCIPAL
import me.ahoo.cosec.runtime.PipelineFixtures.StubEngine
import me.ahoo.cosec.runtime.PipelineFixtures.TENANT
import me.ahoo.cosec.runtime.PipelineFixtures.authenticated
import me.ahoo.cosec.runtime.PipelineFixtures.authenticator
import me.ahoo.cosec.runtime.PipelineFixtures.claims
import me.ahoo.cosec.runtime.PipelineFixtures.facts
import me.ahoo.cosec.runtime.PipelineFixtures.pipeline
import me.ahoo.cosec.runtime.authn.Authenticator
import me.ahoo.cosec.runtime.authn.AuthnResult
import me.ahoo.cosec.runtime.authn.Credentials
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** RFC 0006 §5.8 and §6.1: each stage of [Pipeline.decide], with test doubles. */
class PipelineTest {
    private fun Outcome.assertProblem(type: ProblemType) {
        status.assert().isEqualTo(type.status)
        proceed.assert().isFalse()
        problem!!.type.assert().isEqualTo(type)
        problem!!.requestId.assert().isEqualTo(requestId)
        headers.assert().containsEntry("CoSec-Request-Id", requestId)
        headers.assert().containsEntry("Content-Type", "application/problem+json")
    }

    private fun Outcome.unavailability(): Unavailability = (basis as Basis.Unavailable).reason

    @Test
    fun permitProceedsWithoutABody() = runTest {
        val outcome = pipeline(engine = StubEngine(PERMIT)).evaluate(facts())
        outcome.status.assert().isEqualTo(200)
        outcome.proceed.assert().isTrue()
        outcome.problem.assert().isNull()
        outcome.requestId.assert().isEqualTo(GENERATED_ID)
        outcome.headers.assert().isEqualTo(mapOf("CoSec-Request-Id" to GENERATED_ID))
    }

    @Test
    fun requestIdComesFromTheHeader() = runTest {
        val outcome = pipeline(engine = StubEngine(EXPLICIT_DENY))
            .evaluate(facts(headers = mapOf("cosec-request-id" to listOf(" ", "client-id"))))
        outcome.requestId.assert().isEqualTo("client-id")
        outcome.problem!!.requestId.assert().isEqualTo("client-id")
    }

    @Test
    fun authenticatedDenyIsForbiddenWhetherExplicitOrImplicit() = runTest {
        val authn = authenticator(authenticated())
        val explicit = pipeline(authn, StubEngine(EXPLICIT_DENY)).evaluate(facts())
        val implicit = pipeline(authn, StubEngine(IMPLICIT_DENY)).evaluate(facts())
        explicit.assertProblem(ProblemType.FORBIDDEN)
        implicit.assertProblem(ProblemType.FORBIDDEN)
        ProblemEncoder.encode(explicit.problem!!).assert().isEqualTo(ProblemEncoder.encode(implicit.problem!!))
    }

    @Test
    fun anonymousDenyIsUnauthorized() = runTest {
        pipeline(ANONYMOUS, StubEngine(EXPLICIT_DENY)).evaluate(facts()).assertProblem(ProblemType.UNAUTHORIZED)
        pipeline(ANONYMOUS, StubEngine(IMPLICIT_DENY)).evaluate(facts()).assertProblem(ProblemType.UNAUTHORIZED)
    }

    @ParameterizedTest
    @EnumSource(AuthnResult.RejectReason::class)
    fun rejectedCredentialsDenyWithTheirReason(reason: AuthnResult.RejectReason) = runTest {
        val outcome = pipeline(authenticator(AuthnResult.Rejected(reason)), StubEngine(IMPLICIT_DENY)).evaluate(facts())
        val expected = when (reason) {
            AuthnResult.RejectReason.TOKEN_EXPIRED -> ProblemType.TOKEN_EXPIRED
            AuthnResult.RejectReason.TOKEN_REVOKED -> ProblemType.TOKEN_REVOKED
            AuthnResult.RejectReason.TOKEN_INVALID, AuthnResult.RejectReason.API_KEY_INVALID -> ProblemType.TOKEN_INVALID
        }
        outcome.assertProblem(expected)
    }

    @Test
    fun rejectedCredentialsAreEvaluatedAsAnonymous() = runTest {
        val engine = StubEngine(PERMIT)
        val outcome = pipeline(authenticator(AuthnResult.Rejected(AuthnResult.RejectReason.TOKEN_EXPIRED)), engine)
            .evaluate(facts())
        outcome.proceed.assert().isTrue()
        val request = engine.requests.single()
        request.request.subject.authenticated.assert().isFalse()
        request.request.subject.id.assert().isEqualTo("(0)")
        request.scope.tenant.value.assert().isEqualTo("(0)")
    }

    @Test
    fun scopeRejectedForAnAuthenticatedPrincipal() = runTest {
        val engine = StubEngine(PERMIT)
        val outcome = pipeline(authenticator(authenticated(claims("team-a" to "admin"))), engine)
            .evaluate(facts(headers = mapOf("CoSec-Space-Id" to listOf("team-b"))))
        outcome.assertProblem(ProblemType.SCOPE_REJECTED)
        engine.requests.assert().isEmpty()
    }

    /** `SCP-3`: an anonymous request naming a space is 401, and a rejected credential still names its reason. */
    @Test
    fun scopeRejectedForAnAnonymousPrincipal() = runTest {
        val space = facts(headers = mapOf("CoSec-Space-Id" to listOf("team-a")))
        pipeline(ANONYMOUS).evaluate(space).assertProblem(ProblemType.UNAUTHORIZED)
        pipeline(authenticator(AuthnResult.Rejected(AuthnResult.RejectReason.TOKEN_REVOKED))).evaluate(space)
            .assertProblem(ProblemType.TOKEN_REVOKED)
    }

    @Test
    fun hostAppMismatchIsScopeRejected() = runTest {
        pipeline(authenticator(authenticated())).evaluate(
            facts(headers = mapOf("CoSec-App-Id" to listOf("other")), host = HostScope(AppId("orders")))
        ).assertProblem(ProblemType.SCOPE_REJECTED)
    }

    @ParameterizedTest
    @ValueSource(strings = ["/public/../admin", "/public/%2e%2e/admin", "/a/%zz", "/public/..;/admin"])
    fun ambiguousPathIsInvalidRequestBeforeAuthentication(path: String) = runTest {
        var authenticated = false
        val engine = StubEngine(PERMIT)
        val outcome = pipeline(
            Authenticator {
                authenticated = true
                AuthnResult.Anonymous
            },
            engine
        ).evaluate(facts(path = path))
        outcome.assertProblem(ProblemType.INVALID_REQUEST)
        authenticated.assert().isFalse()
        engine.requests.assert().isEmpty()
    }

    /** `SCP-4`. */
    @Test
    fun conflictingCoSecParameterIsInvalidRequest() = runTest {
        val engine = StubEngine(PERMIT)
        pipeline(engine = engine).evaluate(
            facts(query = "CoSec-App-Id=b", headers = mapOf("CoSec-App-Id" to listOf("a")))
        ).assertProblem(ProblemType.INVALID_REQUEST)
        engine.requests.assert().isEmpty()

        pipeline(
            engine = engine
        ).evaluate(facts(query = "CoSec-App-Id=a", headers = mapOf("CoSec-App-Id" to listOf("a"))))
            .proceed.assert().isTrue()
        engine.requests.single().scope.app.value.assert().isEqualTo("a")
    }

    @Test
    fun engineReadsTheNormalizedRequest() = runTest {
        val engine = StubEngine(PERMIT)
        pipeline(authenticator(authenticated(claims("" to "admin"))), engine).evaluate(
            facts(path = "/orders;jsessionid=1/1", query = "CoSec-Device-Id=phone&CoSec-App-Id=shop")
        )
        val request = engine.requests.single()
        request.request.resource.id.assert().isEqualTo("/orders/1")
        request.request.action.namespace.assert().isEqualTo("http")
        request.request.action.verb.assert().isEqualTo("GET")
        request.request.environment.deviceId.assert().isEqualTo("phone")
        request.request.environment.remoteIp.assert().isEqualTo("10.0.0.1")
        request.request.environment.requestId.assert().isEqualTo(GENERATED_ID)
        request.request.subject.id.assert().isEqualTo(PRINCIPAL.value)
        request.scope.tenant.assert().isEqualTo(TENANT)
        request.scope.app.value.assert().isEqualTo("shop")
        request.scope.roles.map { it.value }.assert().containsExactly("admin")
    }

    @Test
    fun theRealEngineDeniesImplicitlyOnEmptyDocuments() = runTest {
        pipeline(authenticator(authenticated()), CoSecFlatEngine).evaluate(facts()).assertProblem(ProblemType.FORBIDDEN)
    }

    @Test
    fun authenticatorUnavailableIs503() = runTest {
        val failure = IllegalStateException("store down")
        val outcome = pipeline(authenticator(AuthnResult.Unavailable(failure))).evaluate(facts())
        outcome.assertProblem(ProblemType.UNAVAILABLE)
        (outcome.unavailability() as Unavailability.AuthenticatorUnavailable).error.assert().isSameAs(failure)
    }

    @Test
    fun authenticatorThatThrowsIs503() = runTest {
        val outcome = pipeline(Authenticator { throw IllegalStateException("bug in an SPI") }).evaluate(facts())
        outcome.assertProblem(ProblemType.UNAVAILABLE)
    }

    @Test
    fun authenticatorThatThrowsAStrayCancellationIs503() = runTest {
        val outcome = pipeline(Authenticator { throw CancellationException("not ours") }).evaluate(facts())
        outcome.assertProblem(ProblemType.UNAVAILABLE)
        (outcome.unavailability() as Unavailability.AuthenticatorUnavailable).error.assert()
            .isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun authenticatorTimeoutIs503() = runTest {
        val outcome = pipeline(Authenticator { awaitCancellation() }).evaluate(facts())
        outcome.assertProblem(ProblemType.UNAVAILABLE)
        (outcome.unavailability() as Unavailability.AuthenticatorUnavailable).error.assert()
            .isInstanceOf(TimeoutException::class.java)
    }

    @Test
    fun pipelineDeadlineIs503() = runTest {
        val options = PipelineOptions(timeout = 100.milliseconds, authenticationTimeout = 10.seconds)
        val outcome = pipeline(Authenticator { awaitCancellation() }, options = options).evaluate(facts())
        outcome.assertProblem(ProblemType.UNAVAILABLE)
        outcome.unavailability().assert().isEqualTo(Unavailability.DeadlineExceeded)
    }

    @Test
    fun claimedMembershipsReachTheEngine() = runTest {
        val engine = StubEngine(PERMIT)
        val memberships = claims("" to "admin")
        pipeline(authenticator(authenticated(memberships)), engine).evaluate(facts())
        engine.requests.single().memberships.assert().isSameAs(memberships)
    }

    @Test
    fun providedMembershipsReachTheEngine() = runTest {
        val engine = StubEngine(PERMIT)
        val provided = Memberships(Memberships.Source.PROVIDER, emptyMap(), emptyList())
        val provider = MembershipProvider { tenant, principal ->
            tenant.assert().isEqualTo(TENANT)
            principal.assert().isEqualTo(PRINCIPAL)
            CompletableFuture.completedFuture(provided)
        }
        pipeline(authenticator(authenticated(null)), engine, membershipProvider = provider).evaluate(facts())
            .proceed.assert().isTrue()
        engine.requests.single().memberships.assert().isSameAs(provided)
    }

    @Test
    fun membershipsWithoutAProviderAre503() = runTest {
        val outcome = pipeline(authenticator(authenticated(null))).evaluate(facts())
        outcome.assertProblem(ProblemType.UNAVAILABLE)
        ((outcome.unavailability() as Unavailability.Indeterminate).cause).assert()
            .isInstanceOf(Cause.MembershipUnavailable::class.java)
    }

    @Test
    fun failingLateOrWrongMembershipsAre503() = runTest {
        val providers = listOf(
            MembershipProvider { _, _ -> CompletableFuture.failedFuture(IllegalStateException("down")) },
            MembershipProvider { _, _ -> throw IllegalStateException("bug") },
            MembershipProvider { _, _ -> CompletableFuture() },
            MembershipProvider { _, _ -> CompletableFuture.completedFuture(claims()) },
        )
        providers.forEach { provider ->
            val engine = StubEngine(PERMIT)
            val outcome = pipeline(authenticator(authenticated(null)), engine, membershipProvider = provider)
                .evaluate(facts())
            outcome.assertProblem(ProblemType.UNAVAILABLE)
            ((outcome.unavailability() as Unavailability.Indeterminate).cause).assert()
                .isInstanceOf(Cause.MembershipUnavailable::class.java)
            engine.requests.assert().isEmpty()
        }
    }

    @Test
    fun snapshotIsLoadedWhenNotInstalled() = runTest {
        val installed = InstalledViews()
        val outcome = pipeline(views = LoadingViews(installed::load)).evaluate(facts())
        outcome.proceed.assert().isTrue()
        installed.loads.assert().hasSize(1)
    }

    @Test
    fun snapshotSourceFailureIs503() = runTest {
        val failure = IllegalStateException("closed")
        val engine = StubEngine(PERMIT)
        val outcome = pipeline(engine = engine, views = LoadingViews { CompletableFuture.failedFuture(failure) })
            .evaluate(facts())
        outcome.assertProblem(ProblemType.UNAVAILABLE)
        val cause = (outcome.unavailability() as Unavailability.Indeterminate).cause as Cause.SourceUnavailable
        cause.document.assert().isEqualTo(DocumentKind.PLATFORM)
        cause.error.assert().isSameAs(failure)
        engine.requests.assert().isEmpty()
    }

    @Test
    fun indeterminateDecisionIs503() = runTest {
        val indeterminate = Decision.Indeterminate(Cause.NotLoaded(DocumentKind.TENANT))
        val outcome = pipeline(engine = StubEngine(indeterminate)).evaluate(facts())
        outcome.assertProblem(ProblemType.UNAVAILABLE)
        (outcome.unavailability() as Unavailability.Indeterminate).cause.assert().isSameAs(indeterminate.cause)
    }

    @Test
    fun unmodelledExceptionIs500() = runTest {
        val defect = IllegalArgumentException("a defect with a secret message")
        val outcome = pipeline(engine = StubEngine { throw defect }).evaluate(facts())
        outcome.assertProblem(ProblemType.INTERNAL_ERROR)
        // Not the same instance: with assertions on, coroutines recover stack traces into a copy.
        (outcome.basis as Basis.Error).error.assert().isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage(defect.message)
    }

    /** A CancellationException nobody asked for is not a cancellation: it must not make the request vanish. */
    @Test
    fun strayCancellationOutsideAnSpiIs500() = runTest {
        val outcome = pipeline(engine = StubEngine { throw CancellationException("not ours") }).evaluate(facts())
        outcome.assertProblem(ProblemType.INTERNAL_ERROR)
    }

    @Test
    fun credentialsAreReadLikeTheDefaultParser() = runTest {
        val seen = ArrayList<Credentials>()
        val recording = Authenticator {
            seen += it
            AuthnResult.Anonymous
        }
        val query = PipelineOptions(allowQueryToken = true)
        pipeline(recording).evaluate(facts())
        pipeline(recording).evaluate(
            facts(
                headers = mapOf("Authorization" to listOf("header")),
                cookies = mapOf("authorization" to listOf("cookie"))
            )
        )
        pipeline(
            recording
        ).evaluate(facts(query = "authorization=query", cookies = mapOf("authorization" to listOf("cookie"))))
        pipeline(recording, options = query).evaluate(
            facts(query = "authorization=query", cookies = mapOf("authorization" to listOf("cookie")))
        )
        pipeline(recording).evaluate(facts(headers = mapOf("x-api-key" to listOf("key"))))

        seen.map { it.isEmpty }.assert().containsExactly(true, false, false, false, false)
        seen.map { it.token }.assert().containsExactly(null, "header", "cookie", "query", null)
        seen.map { it.apiKey }.assert().containsExactly(null, null, null, null, "key")
        seen[1].toString().assert().doesNotContain("header")
    }
}
