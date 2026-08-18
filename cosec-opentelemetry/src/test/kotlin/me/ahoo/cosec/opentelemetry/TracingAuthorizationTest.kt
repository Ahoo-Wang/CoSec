package me.ahoo.cosec.opentelemetry

import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import me.ahoo.cosec.api.audit.AuditEvent
import me.ahoo.cosec.api.audit.AuditEventSink
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.policy.VerifyResult
import me.ahoo.cosec.audit.AuditingAuthorization
import me.ahoo.cosec.authorization.PolicyVerifyContext
import me.ahoo.cosec.authorization.RoleVerifyContext
import me.ahoo.cosec.authorization.VerifyContext.Companion.setVerifyContext
import me.ahoo.cosec.context.RequestSecurityContexts.setRequest
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.test.asserts.assert
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers
import org.junit.jupiter.api.Test
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test
import java.lang.RuntimeException
import java.net.URI

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
    fun getVersion() {
        assertThat(CoSecInstrumenter.INSTRUMENTATION_VERSION, Matchers.notNullValue())
    }

    @Test
    fun authorize() {
        val authorization = mockk<Authorization> {
            every { authorize(any(), any()) } returns AuthorizeResult.ALLOW.toMono()
        }
        val tracingAuthorization = TracingAuthorization(authorization)
        val request = TestRequest()
        val securityContext = SimpleSecurityContext.anonymous()
        tracingAuthorization.authorize(request, securityContext)
            .test()
            .expectNext(AuthorizeResult.ALLOW)
            .verifyComplete()
    }

    @Test
    fun authorizeWithError() {
        val authorization = mockk<Authorization> {
            every { authorize(any(), any()) } returns RuntimeException().toMono()
        }
        val tracingAuthorization = TracingAuthorization(authorization)
        val request = TestRequest()
        val securityContext = SimpleSecurityContext.anonymous()
        tracingAuthorization.authorize(request, securityContext)
            .test()
            .expectError(RuntimeException::class.java)
            .verify()
    }

    @Test
    fun authorizeWithRoleVerifyContext() {
        val authorization = mockk<Authorization> {
            every { authorize(any(), any()) } returns AuthorizeResult.ALLOW.toMono()
        }
        val tracingAuthorization = TracingAuthorization(authorization)
        val request = TestRequest()
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
        val authorization = mockk<Authorization> {
            every { authorize(any(), any()) } returns AuthorizeResult.IMPLICIT_DENY.toMono()
        }
        val tracingAuthorization = TracingAuthorization(authorization)
        val request = TestRequest()
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

    @Test
    fun authorizeWithRequest() {
        val authorization = mockk<Authorization> {
            every { authorize(any(), any()) } returns AuthorizeResult.ALLOW.toMono()
        }
        val tracingAuthorization = TracingAuthorization(authorization)
        val request = TestRequest(
            appId = "appId",
            deviceId = "deviceId",
            requestId = "requestId",
            spaceId = "spaceId",
        )
        val verifyContext = mockk<RoleVerifyContext> {
            every { roleId } returns "roleId"
            every { permission } returns mockk {
                every { id } returns "permissionId"
            }
            every { result } returns VerifyResult.ALLOW
        }
        val securityContext = SimpleSecurityContext.anonymous()
        securityContext.setVerifyContext(verifyContext)
        securityContext.setRequest(request)
        tracingAuthorization.authorize(request, securityContext)
            .test()
            .expectNext(AuthorizeResult.ALLOW)
            .verifyComplete()
    }

    @Test
    fun auditCapturesAuthorizationSpanWithoutParent() {
        val event = authorizeAndAudit(TestRequest())
        val trace = requireNotNull(event.trace)

        trace.traceId.assert().isNotBlank()
        trace.spanId.assert().isNotBlank()
    }

    @Test
    fun invalidSpanContextDoesNotAddAuditTrace() {
        val request = TestRequest()

        request.withAuditTrace(SpanContext.getInvalid()).assert().isSameAs(request)
    }

    @Test
    fun auditCapturesAuthorizationSpanInsteadOfParent() {
        val parent = GlobalOpenTelemetry.getTracer("test").spanBuilder("parent").startSpan()

        val event = parent.makeCurrent().use {
            authorizeAndAudit(TestRequest())
        }
        val trace = requireNotNull(event.trace)

        trace.traceId.assert().isEqualTo(parent.spanContext.traceId)
        trace.spanId.assert().isNotEqualTo(parent.spanContext.spanId)
        parent.end()
    }

    private fun authorizeAndAudit(request: Request): AuditEvent {
        val events = mutableListOf<AuditEvent>()
        val delegate = mockk<Authorization> {
            every { authorize(any(), any()) } returns AuthorizeResult.ALLOW.toMono()
        }
        val authorization = TracingAuthorization(AuditingAuthorization(delegate, AuditEventSink(events::add)))

        authorization.authorize(request, SimpleSecurityContext.anonymous()).block()

        return events.single()
    }

    private data class TestRequest(
        override val appId: String = "",
        override val spaceId: String = "",
        override val deviceId: String = "",
        override val requestId: String = "request-1",
        override val attributes: Map<String, String> = emptyMap(),
    ) : Request {
        override val path: String = "/"
        override val method: String = "GET"
        override val remoteIp: String = "127.0.0.1"
        override val origin: URI = URI.create("")
        override val referer: URI = URI.create("")

        override fun getHeader(key: String): String = ""

        override fun getQuery(key: String): String = ""

        override fun getCookieValue(name: String): String = ""

        override fun withAttributes(attributes: Map<String, String>): Request = copy(attributes = attributes)
    }
}
