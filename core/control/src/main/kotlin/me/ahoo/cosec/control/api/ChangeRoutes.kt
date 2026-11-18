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
import me.ahoo.cosec.control.api.ControlHttpSupport.CREATED
import me.ahoo.cosec.control.api.ControlHttpSupport.OK
import me.ahoo.cosec.control.api.ControlHttpSupport.changeResponse
import me.ahoo.cosec.control.api.ControlHttpSupport.get
import me.ahoo.cosec.control.api.ControlHttpSupport.notFound
import me.ahoo.cosec.control.api.ControlHttpSupport.post
import me.ahoo.cosec.control.api.ControlHttpSupport.verbOf
import me.ahoo.cosec.control.api.ControlRequestJson.ack
import me.ahoo.cosec.control.api.ControlRequestJson.changeIdOf
import me.ahoo.cosec.control.api.ControlRequestJson.confirmations
import me.ahoo.cosec.control.api.ControlRequestJson.documentKey
import me.ahoo.cosec.control.api.JsonFields.array
import me.ahoo.cosec.control.api.JsonFields.decoded
import me.ahoo.cosec.control.api.JsonFields.long
import me.ahoo.cosec.control.api.JsonFields.optText
import me.ahoo.cosec.control.api.JsonFields.text
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ChangeRequestCodec
import me.ahoo.cosec.control.change.requireRequest

/** The routes of `/changes` (RFC 0007 §10.2): proposing a change, reading one, and its transitions. */
internal class ChangeRoutes(private val plane: ControlPlane) : RouteHandler {
    override suspend fun handle(
        operator: Operator,
        request: ControlHttpRequest,
        segments: List<String>
    ): ControlHttpResponse {
        if (segments.isEmpty()) {
            return post(request) {
                val key = documentKey(it.text("document"))
                val operations = it.array("operations").map { op ->
                    decoded("operations") { ChangeRequestCodec.operation(key, op) }
                }
                // A change's kind is its entry point's, never the request's (review K1): break-glass is /break-glass.
                requireRequest(
                    !it.has("kind")
                ) { "a change has no kind: propose it here, or request break-glass at /break-glass" }
                changeResponse(
                    plane.propose(
                        operator,
                        key,
                        it.long("base"),
                        operations,
                        it.optText("note").orEmpty(),
                        confirmations(it),
                    ),
                    CREATED
                )
            }
        }
        if (segments.size != 1) return notFound()
        val (idText, verb) = verbOf(segments[0])
        val id = changeIdOf(idText)
        if (verb == null) {
            return get(request) { changeResponse(plane.get(operator, id), OK) }
        }
        return post(request) {
            val change = when (verb) {
                "rebase" -> plane.rebase(operator, id)
                "shadow" -> plane.startShadow(operator, id)
                "skip-shadow" -> plane.skipShadow(operator, id, it.text("reason"))
                "approve" -> plane.approve(operator, id, it.text("candidate"))
                "reject" -> plane.reject(operator, id, it.text("reason"))
                "abandon" -> plane.abandon(operator, id)
                "publish" -> plane.publish(operator, id, it.get("ack")?.let(::ack))
                else -> return@post notFound()
            }
            changeResponse(change, OK)
        }
    }
}
