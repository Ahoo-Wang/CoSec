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

package me.ahoo.cosec.model.decision

import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class DecisionTest {
    private val via = BindingRef.Bound(BindingId("g"), DocumentKind.PLATFORM)
    private val ref = StatementRef.Policy(PolicyId("p"), 0, "read", via)

    @Test
    fun `refs and decisions compare by value`() {
        StatementRef.Policy(
            PolicyId("p"),
            0,
            "read",
            BindingRef.Bound(BindingId("g"), DocumentKind.PLATFORM)
        ).assert().isEqualTo(ref)
        BindingRef.Attached(PolicyId("p")).assert().isEqualTo(BindingRef.Attached(PolicyId("p")))
        Decision.Permit(ref).by.via.assert().isEqualTo(via)
        Decision.Deny(null).by.assert().isNull()
    }

    @Test
    fun `a rejected scope is an explicit deny`() {
        val reason = ScopeResolution.Rejected.Reason.SPACE_NOT_MEMBER
        Decision.Deny(StatementRef.ScopeRejected(reason)).by.assert().isEqualTo(StatementRef.ScopeRejected(reason))
    }

    @Test
    fun `causes carry what failed`() {
        val error = IllegalStateException("down")
        Decision.Indeterminate(
            Cause.MembershipUnavailable(error)
        ).cause.assert().isEqualTo(Cause.MembershipUnavailable(error))
        Cause.MatcherFailed(ref, guard = true, error).guard.assert().isTrue()
        Cause.MissingPolicies(setOf(PolicyId("p"))).policyIds.assert().containsExactly(PolicyId("p"))
        Cause.SourceUnavailable(DocumentKind.TENANT, error).document.assert().isEqualTo(DocumentKind.TENANT)
        Cause.NotLoaded(DocumentKind.APP).document.assert().isEqualTo(DocumentKind.APP)
        DocumentKind.entries.assert().containsExactly(DocumentKind.PLATFORM, DocumentKind.APP, DocumentKind.TENANT)
    }
}
