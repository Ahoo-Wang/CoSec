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

@file:OptIn(ExperimentalCoSecApi::class)

package com.example.iam

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.control.sdk.ControlCaller
import me.ahoo.cosec.control.sdk.ControlRefusedException
import me.ahoo.cosec.control.sdk.ControlService
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import tools.jackson.databind.json.JsonMapper

/** What a proposal left: the change's id and state, and the revision of the document it was proposed on. */
class Proposed(val change: String, val state: String, val revision: Long)

/**
 * An IAM service's own code asking the control plane for a change of a tenant's document, as the user whose request it
 * is serving: the control plane decides it for that user, by the policies, and records it as theirs.
 */
class Onboarding(private val control: ControlService) {
    private val json = JsonMapper.builder().build()

    /** Proposes [request] (the body of `POST /changes`) for [tenant] as the principal of [context]. */
    suspend fun propose(context: SecurityContext, tenant: String, request: String): Proposed {
        val caller = ControlCaller.of(context)
        val change = try {
            json.readTree(control.propose(caller, request))
        } catch (refused: ControlRefusedException) {
            // The route's problem: 403 without detail when the user may not write it, 409 with detail when its base moved.
            throw IllegalStateException("The control plane refused the change: ${refused.status} ${refused.code}", refused)
        }
        val document = json.readTree(control.document(caller, SnapshotKey.Tenant(tenant)))
        return Proposed(change.get("id").asString(), change.get("state").asString(), document.get("revision").asLong())
    }
}
