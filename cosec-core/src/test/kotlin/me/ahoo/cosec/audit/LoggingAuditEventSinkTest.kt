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

package me.ahoo.cosec.audit

import io.github.oshai.kotlinlogging.KLogger
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import me.ahoo.cosec.api.audit.AuditDecision
import me.ahoo.cosec.api.audit.AuditReasonCode
import me.ahoo.cosec.api.audit.AuditSourceType
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Instant

class LoggingAuditEventSinkTest {
    private class Recording {
        val levels = StringBuilder()
        val json = StringBuilder()
    }

    private fun event(decision: AuditDecision) = AuditEventData(
        eventId = "event-1",
        timestamp = Instant.ofEpochMilli(1),
        tenantId = "t1",
        principal = AuditPrincipalData(
            id = "u1",
            authenticated = true,
            roles = setOf("admin@0"),
            policies = emptySet(),
        ),
        request = AuditRequestData(
            id = "req-1",
            appId = "app",
            spaceId = null,
            remoteIp = "1.2.3.4",
            method = "POST",
            path = "/api/orders",
            routeId = null,
            device = AuditDeviceData(id = "device-1", userAgent = "test-agent"),
        ),
        authorization = AuditAuthorizationData(
            decision = decision,
            reasonCode = AuditReasonCode.valueOf(decision.name),
            reason = "Explicit Deny",
            elapsedNanos = 350000,
            source = AuditSourceData(
                type = AuditSourceType.GLOBAL_POLICY,
                policy = AuditPolicyData(
                    id = "deny-write",
                    type = PolicyType.GLOBAL,
                    statement = AuditStatementData(index = 0, name = "deny-orders-write"),
                ),
            ),
        ),
        trace = null,
    )

    private fun loggerFor(recording: Recording): KLogger {
        val logger = mockk<KLogger>()
        val slot = slot<() -> Any>()
        every { logger.debug(capture(slot)) } answers {
            recording.levels.append("debug")
            recording.json.append(slot.captured())
            Unit
        }
        every { logger.warn(capture(slot)) } answers {
            recording.levels.append("warn")
            recording.json.append(slot.captured())
            Unit
        }
        every { logger.error(capture(slot)) } answers {
            recording.levels.append("error")
            recording.json.append(slot.captured())
            Unit
        }
        return logger
    }

    private fun publish(decision: AuditDecision): Recording {
        val recording = Recording()
        val sink = LoggingAuditEventSink(loggerFor(recording))
        sink.publish(event(decision))
        return recording
    }

    @Test
    fun allowLogsAtDebug() {
        val recording = publish(AuditDecision.ALLOW)
        recording.levels.toString().assert().isEqualTo("debug")
        recording.json.toString().assert().contains("\"decision\":\"ALLOW\"")
    }

    @Test
    fun explicitDenyLogsAtWarn() {
        val recording = publish(AuditDecision.EXPLICIT_DENY)
        recording.levels.toString().assert().isEqualTo("warn")
        val json = recording.json.toString()
        json.assert().contains("\"decision\":\"EXPLICIT_DENY\"")
        json.assert().contains("\"principal\":{\"id\":\"u1\",\"authenticated\":true")
        json.assert().contains("\"device\":{\"id\":\"device-1\",\"userAgent\":\"test-agent\"}")
        json.assert().contains("\"policy\":{\"id\":\"deny-write\",\"type\":\"global\"")
        json.assert().contains("\"elapsedNanos\":350000")
    }

    @Test
    fun implicitDenyLogsAtWarn() {
        val recording = publish(AuditDecision.IMPLICIT_DENY)
        recording.levels.toString().assert().isEqualTo("warn")
        recording.json.toString().assert().contains("\"decision\":\"IMPLICIT_DENY\"")
    }

    @Test
    fun tooManyRequestsLogsAtWarn() {
        val recording = publish(AuditDecision.TOO_MANY_REQUESTS)
        recording.levels.toString().assert().isEqualTo("warn")
        recording.json.toString().assert().contains("\"decision\":\"TOO_MANY_REQUESTS\"")
    }

    @Test
    fun errorLogsAtError() {
        val recording = publish(AuditDecision.ERROR)
        recording.levels.toString().assert().isEqualTo("error")
        recording.json.toString().assert().contains("\"decision\":\"ERROR\"")
    }

    @Test
    fun defaultLoggerName() {
        LoggingAuditEventSink.LOGGER_NAME.assert().isEqualTo("me.ahoo.cosec.audit")
    }
}
