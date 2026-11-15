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

package me.ahoo.cosec.runtime.stage

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.flat.FlatRequest
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Environment
import me.ahoo.cosec.model.condition.HttpNames
import me.ahoo.cosec.runtime.http.HttpAttributes
import me.ahoo.cosec.runtime.http.Outcome
import java.time.Clock

/**
 * Stage 6: the engine's request, with the attributes its plan reads that stage A did not fetch (stage B, RFC 0006
 * §5.4.2), each provider in parallel with its own timeout. Ends nothing: an attribute that could not be fetched is
 * unavailable to the conditions that read it.
 */
internal class FetchAttributes(
    private val fetching: AttributeFetching,
    private val clock: Clock,
    apiKeyHeader: String,
) : Stage {
    /** Folded once: no condition reads it ([HttpAttributes], `CND-5`). */
    private val apiKeyHeader = HttpNames.fold(apiKeyHeader)

    override suspend fun run(state: RequestState): Outcome? {
        val facts = state.facts
        val authz = AuthzRequest(
            subject = state.subject,
            action = state.action,
            resource = state.resource,
            environment = Environment(
                remoteIp = facts.remoteAddress,
                requestId = state.requestId,
                deviceId = state.parameters.deviceId,
                blocked = state.blacklist.blocked,
            ),
        )
        val request = FlatRequest(
            authz,
            state.scope,
            state.memberships,
            clock.millis(),
            HttpAttributes(facts, apiKeyHeader)
        )
        state.request = fetching.withPlanned(request, state.view, state.fetched, everyStage = false)
        return null
    }
}
