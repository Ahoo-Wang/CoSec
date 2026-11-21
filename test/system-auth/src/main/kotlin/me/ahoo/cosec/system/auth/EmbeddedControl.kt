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

package me.ahoo.cosec.system.auth

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.control.sdk.ControlCaller
import me.ahoo.cosec.control.sdk.ControlRefusedException
import me.ahoo.cosec.control.sdk.ControlService
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * The IAM that embeds the control plane: the same calls as [ServicePortControl], through the control plane's SDK
 * ([ControlService]) in this process, as the IAM's own principal `svc-iam` of the platform tenant ([ControlCaller.service]).
 * There is no connection and no header: the control plane admits the caller as its gate admits a forwarded identity, decides
 * each call for it by the policies, and records it `via.method: in-process`. A call it refuses answers with the route's
 * problem, as over the network.
 */
@Component
@ConditionalOnProperty("system.control.mode", havingValue = "embedded")
class EmbeddedControl(private val control: ControlService) : IamControl {
    private val caller = ControlCaller.service(SERVICE_PRINCIPAL)

    override suspend fun document(key: String): ControlAnswer = answer(200) {
        control.document(caller, SnapshotKey.Tenant(key.removePrefix("tenant:")))
    }

    override suspend fun apply(key: String, request: String): ControlAnswer = answer(201) {
        control.apply(caller, SnapshotKey.Tenant(key.removePrefix("tenant:")), request)
    }

    override suspend fun propose(request: String): ControlAnswer = answer(201) { control.propose(caller, request) }

    override suspend fun skipShadow(id: String, reason: String): ControlAnswer =
        answer(200) { control.skipShadow(caller, id, reason) }

    override suspend fun publish(id: String): ControlAnswer = answer(200) { control.publish(caller, id) }

    private suspend fun answer(status: Int, call: suspend () -> String): ControlAnswer = try {
        ControlAnswer(status, call())
    } catch (refused: ControlRefusedException) {
        ControlAnswer(refused.status, refused.problem)
    }

    private companion object {
        const val SERVICE_PRINCIPAL = "svc-iam"
    }
}
