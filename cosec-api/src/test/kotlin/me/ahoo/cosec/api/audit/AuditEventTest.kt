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

package me.ahoo.cosec.api.audit

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Instant

class AuditEventTest {
    @Test
    fun auditDecisionValues() {
        val decisions = AuditDecision.entries.map { it.name }.toSet()
        decisions.assert().isEqualTo(
            setOf("ALLOW", "EXPLICIT_DENY", "IMPLICIT_DENY", "TOO_MANY_REQUESTS", "ERROR"),
        )
    }

    @Test
    fun auditEventEquality() {
        val event = AuditEvent(
            timestamp = Instant.ofEpochMilli(1),
            tenantId = "t1",
            principalId = "u1",
            authenticated = true,
            roles = setOf("admin@0"),
            policies = setOf("p1"),
            appId = "app",
            spaceId = "0",
            device = AuditDevice(id = null, userAgent = "test-agent"),
            requestId = "req-1",
            remoteIp = "127.0.0.1",
            method = "GET",
            path = "/api",
            decision = AuditDecision.ALLOW,
            reason = "Allow",
            elapsedNanos = 1,
            match = null,
        )
        event.copy(path = "/other").assert().isNotEqualTo(event)
        event.assert().isEqualTo(event.copy())
    }
}
