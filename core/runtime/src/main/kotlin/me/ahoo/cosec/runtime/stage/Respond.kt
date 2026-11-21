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

package me.ahoo.cosec.runtime.stage

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.runtime.http.Outcome
import me.ahoo.cosec.runtime.http.RequestSecurity
import me.ahoo.cosec.runtime.http.StatusMapping

/**
 * Stage 9: the outcome of the decision, through [StatusMapping] (RFC 0006 §6.1), with who may proceed for a permit
 * (§7.6) and its tightest rate limit. It always answers.
 */
internal object Respond : Stage {
    override suspend fun run(state: RequestState): Outcome {
        val decision = state.decision
        if (decision is Decision.Permit) {
            val request = state.request
            state.security = RequestSecurity(
                state.requestId,
                state.principal,
                request.scope,
                request.memberships,
                request.request,
                request.attributes,
            )
        }
        return StatusMapping.decided(
            state.requestId,
            decision,
            state.authn,
            state.traceId,
            state.security,
            state.tightest
        )
    }
}
