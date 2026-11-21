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

package me.ahoo.cosec.runtime.event

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.decision.Cause

/**
 * The wire name of each kind of [Cause], the one table every record that names a cause writes from (`OBS-9`): the
 * audit record's reason, the shadow report's sample and the control API's `cause`. A name is the record's, not the
 * class's: a renamed `Cause` class changes nothing a consumer reads, and a new one does not compile until it is
 * named here. The names are the spellings 8.0's previews wrote. `cosec-micrometer`'s `cause` tag is another,
 * coarser vocabulary (`OBS-6`).
 */
@InternalCoSecApi
enum class CauseName(val wire: String) {
    MATCHER_FAILED("MatcherFailed"),
    MISSING_POLICIES("MissingPolicies"),
    AMBIGUOUS_POLICY("AmbiguousPolicy"),
    SOURCE_UNAVAILABLE("SourceUnavailable"),
    DOCUMENT_INVALID("DocumentInvalid"),
    NOT_LOADED("NotLoaded"),
    BOUNDARY_UNAVAILABLE("BoundaryUnavailable"),
    ATTRIBUTE_UNAVAILABLE("AttributeUnavailable"),
    CHECK_FAILED("CheckFailed"),
    MEMBERSHIP_UNAVAILABLE("MembershipUnavailable"),
    REQUIRED_LISTENER_FAILED("RequiredListenerFailed"),
    RATE_LIMITER_UNAVAILABLE("RateLimiterUnavailable"),
    ;

    companion object {
        fun of(cause: Cause): CauseName = when (cause) {
            is Cause.MatcherFailed -> MATCHER_FAILED
            is Cause.MissingPolicies -> MISSING_POLICIES
            is Cause.AmbiguousPolicy -> AMBIGUOUS_POLICY
            is Cause.SourceUnavailable -> SOURCE_UNAVAILABLE
            is Cause.DocumentInvalid -> DOCUMENT_INVALID
            is Cause.NotLoaded -> NOT_LOADED
            is Cause.BoundaryUnavailable -> BOUNDARY_UNAVAILABLE
            is Cause.AttributeUnavailable -> ATTRIBUTE_UNAVAILABLE
            is Cause.CheckFailed -> CHECK_FAILED
            is Cause.MembershipUnavailable -> MEMBERSHIP_UNAVAILABLE
            is Cause.RequiredListenerFailed -> REQUIRED_LISTENER_FAILED
            is Cause.RateLimiterUnavailable -> RATE_LIMITER_UNAVAILABLE
        }
    }
}
