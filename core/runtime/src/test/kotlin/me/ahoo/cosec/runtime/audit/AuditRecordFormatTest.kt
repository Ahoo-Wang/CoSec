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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.runtime.audit

import io.github.oshai.kotlinlogging.KLogger
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.engine.AttributeStatus
import me.ahoo.cosec.engine.ViewVersion
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.cosec.model.scope.ScopeSubject
import me.ahoo.cosec.runtime.event.DecisionEvent
import me.ahoo.cosec.runtime.event.OutcomeSummary
import me.ahoo.cosec.runtime.event.RequestSummary
import me.ahoo.cosec.runtime.event.SubjectSummary
import me.ahoo.cosec.runtime.http.TraceContext
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * What leaves the process for a decision, pinned from the event itself: a `DecisionEvent` becomes the audit record
 * (`AuditEvents.from`), which the audit log writes as its line and the Kafka audit listener as its record value, the
 * same JSON. Moving a class the event carries (the trace and `ViewVersion` moved packages in R9) must not change a byte a
 * consumer reads.
 *
 * Everything that carries a decision out of the process, and what pins it; a change to the event or its parts keeps
 * this list true:
 *
 * | Leaves as | Built from the event by | Pinned by |
 * |---|---|---|
 * | the audit log line (`me.ahoo.cosec.audit`) | `AuditEvents.from`, then `CoSecJson.mapper` | this test |
 * | the Kafka audit record value | the same | this test, and `KafkaAuditListenerTest.theRecordValueIsPinned` for the send |
 * | spans (`TracingListener`) | attributes, not JSON | `TracingListenerTest` |
 * | metrics (`MetricsListener`) | tags, not JSON | `MetricsListenerTest` |
 * | problem bodies | the `Outcome`, never the event | `ProblemSchemaTest`, `ProblemBodyPropertyTest` |
 *
 * The evaluation trace is in none of them: only an application's own listeners read it.
 */
class AuditRecordFormatTest {
    private val scope = run {
        val memberships =
            Memberships(Memberships.Source.CLAIMS, mapOf(SpaceId("space-1") to setOf(RoleId("admin"))), emptyList())
        val subject = ScopeSubject(TenantId("tenant-1"), true, memberships, "app-1")
        (ScopeResolver.resolve(subject, "space-1", HostScope(null)) as ScopeResolution.Resolved).scope
    }

    private fun eventWith(trace: TraceContext) = DecisionEvent(
        id = "event-1",
        requestId = "request-1",
        time = Instant.parse("2026-08-18T00:00:00Z").toEpochMilli(),
        elapsedNanos = 100,
        kind = DecisionEvent.Kind.DECIDED,
        outcome = OutcomeSummary(200, true, null, OutcomeSummary.Reason.PERMIT),
        decision = Decision.Permit(
            StatementRef.Policy(
                PolicyId("orders"),
                0,
                "read",
                BindingRef.Bound(BindingId("role:reader@app-1:order.read"), DocumentKind.TENANT),
            )
        ),
        evaluationTrace = null,
        breakGlass = false,
        snapshotVersion = ViewVersion(3, 5, 7),
        subject = SubjectSummary("tenant-1", "principal-1", true, "jwt", null),
        scope = scope,
        action = Action("http", "GET"),
        resource = Resource("http", "/orders/1", emptyMap()),
        request = RequestSummary("GET", "/orders/1", "127.0.0.1", "device-1", "orders"),
        attributes = mapOf("ext.directory.department" to AttributeStatus.PRESENT),
        trace = trace,
        requiredFailures = emptyList(),
        replay = null,
    )

    private val event = eventWith(TraceContext("trace-1", "span-1", true))

    private val pinned = checkNotNull(javaClass.getResource("/stored-format/audit-record.json")).readText().trim()

    @Test
    fun `the audit record of a decision event is pinned`() {
        CoSecJson.mapper.writeValueAsString(AuditEvents.from(event)).assert().isEqualTo(pinned)
    }

    /** #1404: a tracestate is vendor data for the span's parent; the record is what it was without one. */
    @Test
    fun `a tracestate is in no audit record, line or log message`() {
        val withState = eventWith(TraceContext("trace-1", "span-1", true, "vendor=opaque-value,other=x"))
        CoSecJson.mapper.writeValueAsString(AuditEvents.from(withState)).assert().isEqualTo(pinned)
        withState.trace.toString().assert().isEqualTo("TraceContext(trace-1/span-1)")
    }

    @Test
    fun `the audit log writes the pinned record as its line`() {
        val logger = mockk<KLogger>()
        val line = slot<() -> Any>()
        every { logger.debug(capture(line)) } returns Unit
        LoggingAuditEventSink(logger).publish(AuditEvents.from(event))
        line.captured().toString().assert().isEqualTo(pinned)
    }
}
