package me.ahoo.cosec.opentelemetry

import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.policy.VerifyResult
import me.ahoo.cosec.authorization.PolicyVerifyContext
import me.ahoo.cosec.authorization.RoleVerifyContext
import me.ahoo.cosec.authorization.VerifyContext.Companion.setVerifyContext
import me.ahoo.cosec.context.SimpleSecurityContext
import org.junit.jupiter.api.Test
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import java.lang.RuntimeException

class TracingAuthorizationTest {
    companion object {
        init {
            val sdkTracerProvider: SdkTracerProvider = SdkTracerProvider.builder()
                .build()
            OpenTelemetrySdk.builder()
                .setTracerProvider(sdkTracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .buildAndRegisterGlobal()
        }
    }

    @Test
    fun authorize() {
        val authorization = mockk<Authorization>() {
            every { authorize(any(), any()) } returns AuthorizeResult.ALLOW.toMono()
        }
        val tracingAuthorization = TracingAuthorization(authorization)
        val request = mockk<Request>()
        val securityContext = SimpleSecurityContext.anonymous()
        tracingAuthorization.authorize(request, securityContext)
            .test()
            .expectNext(AuthorizeResult.ALLOW)
            .verifyComplete()
    }

    @Test
    fun authorizeWithError() {
        val authorization = mockk<Authorization>() {
            every { authorize(any(), any()) } returns RuntimeException().toMono()
        }
        val tracingAuthorization = TracingAuthorization(authorization)
        val request = mockk<Request>()
        val securityContext = SimpleSecurityContext.anonymous()
        tracingAuthorization.authorize(request, securityContext)
            .test()
            .expectError(RuntimeException::class.java)
            .verify()
    }

    @Test
    fun authorizeWithRoleVerifyContext() {
        val authorization = mockk<Authorization>() {
            every { authorize(any(), any()) } returns AuthorizeResult.ALLOW.toMono()
        }
        val tracingAuthorization = TracingAuthorization(authorization)
        val request = mockk<Request>()
        val verifyContext = mockk<RoleVerifyContext> {
            every { roleId } returns "roleId"
            every { permission } returns mockk {
                every { id } returns "permissionId"
            }
            every { result } returns VerifyResult.ALLOW
        }
        val securityContext = SimpleSecurityContext.anonymous()
        securityContext.setVerifyContext(verifyContext)
        tracingAuthorization.authorize(request, securityContext)
            .test()
            .expectNext(AuthorizeResult.ALLOW)
            .verifyComplete()
    }

    @Test
    fun authorizeWithPolicyVerifyContext() {
        val authorization = mockk<Authorization>() {
            every { authorize(any(), any()) } returns AuthorizeResult.IMPLICIT_DENY.toMono()
        }
        val tracingAuthorization = TracingAuthorization(authorization)
        val request = mockk<Request>()
        val verifyContext = mockk<PolicyVerifyContext> {
            every { policy.id } returns "policyId"
            every { statementIndex } returns 1
            every { statement.name } returns "statementName"
            every { result } returns VerifyResult.IMPLICIT_DENY
        }
        val securityContext = SimpleSecurityContext.anonymous()
        securityContext.setVerifyContext(verifyContext)
        tracingAuthorization.authorize(request, securityContext)
            .test()
            .expectNext(AuthorizeResult.IMPLICIT_DENY)
            .verifyComplete()
    }


}