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

package me.ahoo.cosec.control.api

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.api.ControlHttpSupport.CONFLICT
import me.ahoo.cosec.control.api.ControlHttpSupport.CREATED
import me.ahoo.cosec.control.api.ControlHttpSupport.DELETE
import me.ahoo.cosec.control.api.ControlHttpSupport.OK
import me.ahoo.cosec.control.api.ControlHttpSupport.changeResponse
import me.ahoo.cosec.control.api.ControlHttpSupport.get
import me.ahoo.cosec.control.api.ControlHttpSupport.notFound
import me.ahoo.cosec.control.api.ControlHttpSupport.post
import me.ahoo.cosec.control.api.ControlHttpSupport.verbOf
import me.ahoo.cosec.control.api.ControlRequestJson.changeIdOf
import me.ahoo.cosec.control.api.ControlRequestJson.instantOf
import me.ahoo.cosec.control.api.ControlRequestJson.query
import me.ahoo.cosec.control.api.JsonFields.field
import me.ahoo.cosec.control.api.JsonFields.optText
import me.ahoo.cosec.control.api.JsonFields.text
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.InvalidControlRequestException
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId

/** The routes of `/break-glass` (RFC 0007 §8): requesting it, approving it, revoking it. */
internal class BreakGlassRoutes(private val plane: ControlPlane) : RouteHandler {
    override suspend fun handle(
        operator: Operator,
        request: ControlHttpRequest,
        segments: List<String>
    ): ControlHttpResponse = when {
        segments.isEmpty() -> post(request) {
            val change = plane.breakGlass.request(
                operator,
                it.text("principal").let { p -> field("principal", "a principal id") { PrincipalId(p) } },
                instantOf("expiresAt", it.text("expiresAt")),
                it.text("reason"),
                it.optText("policy")?.let { p -> field("policy", "a policy id") { PolicyId(p) } }
                    ?: me.ahoo.cosec.engine.BuiltinPolicies.ROOT,
            )
            changeResponse(change, CREATED)
        }

        segments.size == 1 && request.method == DELETE -> {
            val reason = request.query["reason"] ?: throw InvalidControlRequestException("Revoking break-glass needs a reason.")
            val binding = field("binding", "a binding id") { BindingId(unescaped(segments[0])) }
            val change = plane.breakGlass.revoke(operator, binding, reason)
            // An emergency revoke that did not take effect never answers as one that did (R4-1), whoever removed it.
            if (plane.breakGlass.grants(binding)) {
                throw ChangeRejectedException(
                    CONFLICT,
                    "binding [${binding.value}] still grants: change ${change.id.value} is ${change.state}"
                )
            }
            changeResponse(change, OK)
        }

        segments.size == 1 && verbOf(segments[0]).second == "approve" -> post(request) {
            changeResponse(
                plane.approve(operator, changeIdOf(verbOf(segments[0]).first), it.text("candidate")),
                OK
            )
        }

        else -> notFound()
    }
}
