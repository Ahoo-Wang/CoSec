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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.runtime.audit

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.audit.AuditDecision
import me.ahoo.cosec.api.audit.AuditReasonCode
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.runtime.event.OutcomeSummary.Reason
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * UD-7: audit's decision is the decision event's reason, word for word, for every reason there is. Only the ways a
 * request is undecided before the engine ran fold into INDETERMINATE (their `kind` says which), so EXPLICIT_DENY is
 * only ever a DENY statement that matched.
 */
class AuditVocabularyTest {
    private val folded = setOf(Reason.AUTHENTICATOR_UNAVAILABLE, Reason.DEADLINE_EXCEEDED, Reason.CANCELLED)

    @Test
    fun `every reason has the audit decision of its own name`() {
        Reason.entries.forEach { reason ->
            val (decision, code) = AuditEvents.decision(reason, null, null)
            val expected = if (reason in folded) AuditDecision.INDETERMINATE.name else reason.name
            decision.name.assert().describedAs(reason.name).isEqualTo(expected)
            code.name.assert().describedAs(reason.name).isEqualTo(expected)
        }
    }

    @Test
    fun `a rejected credential names its reason, a clock ahead included`() {
        AuditEvents.decision(Reason.IMPLICIT_DENY, AuthnResult.RejectReason.TOKEN_NOT_YET_VALID, null)
            .assert().isEqualTo(AuditDecision.IMPLICIT_DENY to AuditReasonCode.TOKEN_NOT_YET_VALID)
        AuditEvents.decision(Reason.IMPLICIT_DENY, AuthnResult.RejectReason.API_KEY_INVALID, null)
            .assert().isEqualTo(AuditDecision.IMPLICIT_DENY to AuditReasonCode.TOKEN_INVALID)
    }

    @Test
    fun `a strict rate limit that could not be counted is named as such`() {
        AuditEvents.decision(Reason.INDETERMINATE, null, Cause.RateLimiterUnavailable("platform/strict"))
            .assert().isEqualTo(AuditDecision.INDETERMINATE to AuditReasonCode.RATE_LIMITER_UNAVAILABLE)
    }
}
