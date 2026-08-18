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

class AuditEventTest {
    @Test
    fun auditModelsAreInterfaces() {
        listOf(
            AuditPrincipal::class,
            AuditDevice::class,
            AuditRequest::class,
            AuditStatement::class,
            AuditPolicy::class,
            AuditPermission::class,
            AuditRole::class,
            AuditSource::class,
            AuditAuthorization::class,
            AuditTrace::class,
            AuditEvent::class,
        ).all { it.java.isInterface }.assert().isTrue()
    }

    @Test
    fun auditDecisionValues() {
        val decisions = AuditDecision.entries.map { it.name }.toSet()
        decisions.assert().isEqualTo(
            setOf("ALLOW", "EXPLICIT_DENY", "IMPLICIT_DENY", "TOO_MANY_REQUESTS", "ERROR"),
        )
    }

    @Test
    fun auditReasonCodeValues() {
        AuditReasonCode.entries.map { it.name }.toSet().assert().isEqualTo(
            setOf(
                "ALLOW",
                "EXPLICIT_DENY",
                "IMPLICIT_DENY",
                "TOKEN_EXPIRED",
                "TOKEN_INVALID",
                "TOO_MANY_REQUESTS",
                "REGEX_TIMEOUT",
                "ERROR",
            ),
        )
    }

    @Test
    fun auditSourceTypeValues() {
        AuditSourceType.entries.map { it.name }.toSet().assert().isEqualTo(
            setOf(
                "ROOT",
                "BLACKLIST",
                "GLOBAL_POLICY",
                "PRINCIPAL_POLICY",
                "ROLE_PERMISSION",
                "NONE",
                "UNKNOWN",
            ),
        )
    }
}
