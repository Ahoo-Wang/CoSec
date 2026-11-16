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
import me.ahoo.cosec.control.api.ControlHttpSupport.changeResponse
import me.ahoo.cosec.control.api.ControlHttpSupport.document
import me.ahoo.cosec.control.api.ControlHttpSupport.factory
import me.ahoo.cosec.control.api.ControlHttpSupport.get
import me.ahoo.cosec.control.api.ControlHttpSupport.json
import me.ahoo.cosec.control.api.ControlHttpSupport.notFound
import me.ahoo.cosec.control.api.ControlHttpSupport.post
import me.ahoo.cosec.control.api.ControlHttpSupport.revision
import me.ahoo.cosec.control.api.ControlHttpSupport.verbOf
import me.ahoo.cosec.control.api.ControlRequestJson.confirmations
import me.ahoo.cosec.control.api.ControlRequestJson.documentKey
import me.ahoo.cosec.control.api.ControlRequestJson.query
import me.ahoo.cosec.control.api.ControlRequestJson.revisionOf
import me.ahoo.cosec.control.api.ControlResponseJson.diff
import me.ahoo.cosec.control.api.ControlResponseJson.entries
import me.ahoo.cosec.control.api.ControlResponseJson.history
import me.ahoo.cosec.control.api.JsonFields.decoded
import me.ahoo.cosec.control.api.JsonFields.field
import me.ahoo.cosec.control.api.JsonFields.long
import me.ahoo.cosec.control.api.JsonFields.need
import me.ahoo.cosec.control.api.JsonFields.optText
import me.ahoo.cosec.control.api.JsonFields.text
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ApplyMode
import me.ahoo.cosec.control.change.ownerOf
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/** The routes of `/documents/{key}` (RFC 0007 §10.2): its reads, `:apply` and `:rollback`. */
internal class DocumentRoutes(private val plane: ControlPlane) : RouteHandler {
    override suspend fun handle(
        operator: Operator,
        request: ControlHttpRequest,
        segments: List<String>
    ): ControlHttpResponse {
        val (keyText, verb) = segments.firstOrNull()?.let(::verbOf) ?: return notFound()
        // `:apply` and `:rollback` end the path (R2-M1 of #1240's review); the reads match their own exact shapes.
        if (verb != null && segments.size != 1) {
            return notFound()
        }
        val key = documentKey(unescaped(keyText))
        return when (verb) {
            null -> revisions(operator, request, key, segments.drop(1))
            "apply" -> post(request) {
                val (kind, owner) = ownerOf(key)
                val desired = decoded("document") { PolicyDocumentCodec.decode(kind, owner, it.need("document")) }
                val mode = it.text("mode").let { mode ->
                    field("mode", "one of ${ApplyMode.entries.joinToString()}") { ApplyMode.valueOf(mode) }
                }
                changeResponse(plane.apply(operator, key, desired, mode, it.optText("note").orEmpty()), CREATED)
            }

            "rollback" -> post(request) {
                changeResponse(
                    plane.rollback(operator, key, it.long("revision"), it.optText("note").orEmpty(), confirmations(it)),
                    CREATED
                )
            }

            else -> notFound()
        }
    }

    /** The reads of document [key]: its head, its history, a revision, a diff, a revision's propagation. */
    private suspend fun revisions(
        operator: Operator,
        request: ControlHttpRequest,
        key: SnapshotKey,
        rest: List<String>
    ): ControlHttpResponse =
        get(request) {
            when {
                rest.isEmpty() -> document(plane.read(operator, key))
                rest == listOf("diff") -> json(
                    diff(plane.diff(operator, key, request.revision("from"), request.revision("to")))
                )
                rest == listOf("revisions") -> json(
                    history(
                        plane.history(
                            operator,
                            key,
                            request.query["before"]?.let { revisionOf("before", it) },
                            request.query["limit"]?.let { limit -> field("limit", "a number") { limit.toInt() } }
                                ?: ControlPlane.DEFAULT_LIMIT
                        )
                    )
                )
                rest.size == 2 && rest[0] == "revisions" -> document(
                    plane.read(operator, key, revisionOf("revision", unescaped(rest[1])))
                )
                rest.size == 3 && rest[0] == "revisions" && rest[2] == "propagation" -> {
                    val propagation = plane.propagation(operator, key, revisionOf("revision", unescaped(rest[1])))
                    json(
                        factory.objectNode().put(
                            "revision",
                            propagation.revision
                        ).put("installed", propagation.installed).put("recent", propagation.recent)
                    )
                }

                else -> notFound()
            }
        }
}
