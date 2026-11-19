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

package me.ahoo.cosec.micrometer

import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** The `cause` tag of every kind of cause: a closed set of values, never an id or a message. */
class CauseTagTest {
    @Test
    fun `every cause has its tag`() {
        val error = IllegalStateException("down")
        val at = StatementRef.Policy(PolicyId("p"), 0, "s", BindingRef.Attached(PolicyId("p")))
        val tags = mapOf(
            Cause.SourceUnavailable(DocumentKind.PLATFORM, error) to "source_unavailable",
            Cause.NotLoaded(DocumentKind.TENANT) to "source_unavailable",
            Cause.DocumentInvalid(DocumentKind.APP, 1, error) to "document_invalid",
            Cause.MissingPolicies(setOf(PolicyId("p"))) to "missing_policies",
            Cause.AmbiguousPolicy(setOf(PolicyId("p"))) to "missing_policies",
            Cause.MembershipUnavailable(error) to "membership",
            Cause.AttributeUnavailable(setOf("ext.a"), null) to "attribute",
            Cause.MatcherFailed(at, false, error) to "matcher",
            Cause.RequiredListenerFailed(listOf("audit")) to "required_listener",
            Cause.BoundaryUnavailable(PolicyId("b"), error) to "boundary",
            Cause.RateLimiterUnavailable("r") to "rate_limiter",
            Cause.CheckFailed(error) to "check",
        )
        tags.forEach { (cause, tag) -> MetricsListener.causeTag(cause).assert().isEqualTo(tag) }
        MetricsListener.causeTag(null).assert().isEqualTo("unknown")
    }
}
