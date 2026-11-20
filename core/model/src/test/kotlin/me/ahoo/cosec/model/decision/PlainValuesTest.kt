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

package me.ahoo.cosec.model.decision

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.PolicyId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * R2A-1: the decision values are plain classes, with `equals`, `hashCode` and `toString` written out. Each compares by
 * every field it has, and says every field in its text, so a field added later that someone forgets in one of the three is
 * a failing line here.
 */
class PlainValuesTest {
    private val ref = StatementRef.Policy(
        PolicyId("p"),
        0,
        "read",
        BindingRef.Bound(BindingId("g"), DocumentKind.TENANT)
    )
    private val other = StatementRef.Policy(PolicyId("q"), 1, "write", BindingRef.Builtin)
    private val boom = IllegalStateException("boom")
    private val bang = IllegalStateException("bang")
    private val spec = RateLimitSpec(1.0)

    /** [value] equals [same] and nothing in [differs]; the hash agrees with the equality; the text is [text]. */
    private fun <T : Any> pin(value: T, same: T, differs: List<T>, text: String) {
        value.assert().isEqualTo(value)
        value.assert().isEqualTo(same)
        value.hashCode().assert().isEqualTo(same.hashCode())
        for (not in differs) {
            value.assert().isNotEqualTo(not)
        }
        val nothing: Any? = null
        value.assert().isNotEqualTo(nothing)
        value.equals("not a decision value").assert().isFalse()
        value.toString().assert().isEqualTo(text)
    }

    @Test
    fun decisions() {
        val obligation = Obligation.RateLimit("r", "b", spec, false)
        pin(
            Decision.Permit(ref, listOf(obligation)),
            Decision.Permit(ref, listOf(Obligation.RateLimit("r", "b", spec, false))),
            listOf(Decision.Permit(other, listOf(obligation)), Decision.Permit(ref)),
            "Permit(by=$ref, obligations=[$obligation])"
        )
        pin(Decision.Permit(ref), Decision.Permit(ref, emptyList()), emptyList(), "Permit(by=$ref, obligations=[])")
        pin(Decision.Deny(ref), Decision.Deny(ref), listOf(Decision.Deny(null), Decision.Deny(other)), "Deny(by=$ref)")
        pin(Decision.Deny(null), Decision.Deny(null), listOf(Decision.Deny(ref)), "Deny(by=null)")
        pin(
            Decision.Indeterminate(Cause.NotLoaded(DocumentKind.APP)),
            Decision.Indeterminate(Cause.NotLoaded(DocumentKind.APP)),
            listOf(Decision.Indeterminate(Cause.NotLoaded(DocumentKind.TENANT))),
            "Indeterminate(cause=NotLoaded(document=APP))"
        )
    }

    @Test
    fun theCausesOfAFailedStep() {
        pin(
            Cause.MatcherFailed(ref, true, boom),
            Cause.MatcherFailed(ref, true, boom),
            listOf(
                Cause.MatcherFailed(other, true, boom),
                Cause.MatcherFailed(ref, false, boom),
                Cause.MatcherFailed(ref, true, bang),
            ),
            "MatcherFailed(at=$ref, guard=true, error=$boom)"
        )
    }

    @Test
    fun theCausesOfDocumentsAndPolicies() {
        pin(
            Cause.MissingPolicies(setOf(PolicyId("a"))),
            Cause.MissingPolicies(setOf(PolicyId("a"))),
            listOf(Cause.MissingPolicies(setOf(PolicyId("b")))),
            "MissingPolicies(policyIds=[PolicyId(value=a)])"
        )
        pin(
            Cause.AmbiguousPolicy(setOf(PolicyId("a"))),
            Cause.AmbiguousPolicy(setOf(PolicyId("a"))),
            listOf(Cause.AmbiguousPolicy(setOf(PolicyId("b")))),
            "AmbiguousPolicy(policyIds=[PolicyId(value=a)])"
        )
        pin(
            Cause.SourceUnavailable(DocumentKind.APP, boom),
            Cause.SourceUnavailable(DocumentKind.APP, boom),
            listOf(Cause.SourceUnavailable(DocumentKind.TENANT, boom), Cause.SourceUnavailable(DocumentKind.APP, bang)),
            "SourceUnavailable(document=APP, error=$boom)"
        )
        pin(
            Cause.DocumentInvalid(DocumentKind.APP, 3, boom),
            Cause.DocumentInvalid(DocumentKind.APP, 3, boom),
            listOf(
                Cause.DocumentInvalid(DocumentKind.TENANT, 3, boom),
                Cause.DocumentInvalid(DocumentKind.APP, 4, boom),
                Cause.DocumentInvalid(DocumentKind.APP, 3, bang),
            ),
            "DocumentInvalid(document=APP, revision=3, error=$boom)"
        )
        pin(
            Cause.BoundaryUnavailable(PolicyId("b"), boom),
            Cause.BoundaryUnavailable(PolicyId("b"), boom),
            listOf(Cause.BoundaryUnavailable(PolicyId("c"), boom), Cause.BoundaryUnavailable(PolicyId("b"), bang)),
            "BoundaryUnavailable(boundary=PolicyId(value=b), error=$boom)"
        )
    }

    @Test
    fun theCausesOfPortsAndAttributes() {
        pin(
            Cause.AttributeUnavailable(setOf("ext.a"), ref),
            Cause.AttributeUnavailable(setOf("ext.a"), ref),
            listOf(Cause.AttributeUnavailable(setOf("ext.b"), ref), Cause.AttributeUnavailable(setOf("ext.a"), null)),
            "AttributeUnavailable(attributes=[ext.a], at=$ref)"
        )
        pin(
            Cause.AttributeUnavailable(setOf("ext.a"), null),
            Cause.AttributeUnavailable(setOf("ext.a"), null),
            listOf(Cause.AttributeUnavailable(setOf("ext.a"), ref)),
            "AttributeUnavailable(attributes=[ext.a], at=null)"
        )
        pin(
            Cause.CheckFailed(boom),
            Cause.CheckFailed(boom),
            listOf(Cause.CheckFailed(bang)),
            "CheckFailed(error=$boom)"
        )
        pin(
            Cause.MembershipUnavailable(boom),
            Cause.MembershipUnavailable(boom),
            listOf(Cause.MembershipUnavailable(bang)),
            "MembershipUnavailable(error=$boom)"
        )
        pin(
            Cause.RequiredListenerFailed(listOf("a")),
            Cause.RequiredListenerFailed(listOf("a")),
            listOf(Cause.RequiredListenerFailed(listOf("b"))),
            "RequiredListenerFailed(listeners=[a])"
        )
        pin(
            Cause.RateLimiterUnavailable("r"),
            Cause.RateLimiterUnavailable("r"),
            listOf(Cause.RateLimiterUnavailable("s")),
            "RateLimiterUnavailable(ruleId=r)"
        )
    }

    @Test
    fun theRateLimitObligation() {
        pin(
            Obligation.RateLimit("r", "b", spec, false),
            Obligation.RateLimit("r", "b", RateLimitSpec(1.0), false),
            listOf(
                Obligation.RateLimit("s", "b", spec, false),
                Obligation.RateLimit("r", "c", spec, false),
                Obligation.RateLimit("r", null, spec, false),
                Obligation.RateLimit("r", "b", RateLimitSpec(2.0), false),
                Obligation.RateLimit("r", "b", spec, true),
            ),
            "RateLimit(ruleId=r, bucket=b, limit=$spec, strictFailure=false)"
        )
        pin(
            Obligation.RateLimit("r", null, spec, true),
            Obligation.RateLimit("r", null, spec, true),
            listOf(Obligation.RateLimit("r", "b", spec, true)),
            "RateLimit(ruleId=r, bucket=null, limit=$spec, strictFailure=true)"
        )
    }
}
