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
import me.ahoo.cosec.control.api.ControlHttpSupport.json
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
import me.ahoo.cosec.control.api.JsonFields.optBool
import me.ahoo.cosec.control.api.JsonFields.optText
import me.ahoo.cosec.control.api.JsonFields.text
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRequestCodec
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.Confirmation
import me.ahoo.cosec.control.change.InvalidControlRequestException
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.change.requireRequest
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import tools.jackson.databind.JsonNode

/** The routes of `/changes` (RFC 0007 §10.2): proposing a change, reading one, and its transitions. */
internal class ChangeRoutes(
    private val plane: ControlPlane,
    private val profile: ControlProfile = ControlProfile.OPERATORS,
) : RouteHandler {
    override suspend fun handle(
        operator: Operator,
        request: ControlHttpRequest,
        segments: List<String>
    ): ControlHttpResponse {
        if (segments.isEmpty()) {
            return proposeOrList(operator, request)
        }
        if (segments.size != 1) return notFound()
        val (idText, verb) = verbOf(segments[0])
        val id = changeIdOf(idText)
        if (!served(operator, id)) return notFound()
        if (verb == null) {
            return get(request) {
                val change = plane.get(operator, id)
                changeResponse(plane, operator, change, OK, plane.acknowledgements.of(change))
            }
        }
        return post(request) {
            val change = when (verb) {
                "rebase" -> plane.rebase(operator, id)
                "shadow" -> plane.startShadow(operator, id)
                "skip-shadow" -> plane.skipShadow(operator, id, it.text("reason"))
                "approve" -> plane.approve(operator, id, it.text("candidate"))
                "reject" -> plane.reject(operator, id, it.text("reason"))
                "abandon" -> plane.abandon(operator, id)
                "publish" -> plane.publish(operator, id, it.get("ack")?.let(::ack), it.optBool("lockout") ?: false)
                else -> return@post notFound()
            }
            changeResponse(plane, operator, change, OK)
        }
    }

    /**
     * Whether this profile serves the routes of change [id] (`CTL-58`): every change on the operators' profile; on the
     * service profile only a tenant document's, once its record, read as [operator], says so.
     */
    private suspend fun served(operator: Operator, id: ChangeId): Boolean =
        profile == ControlProfile.OPERATORS || profile.admitsChange(plane.get(operator, id).key)

    /** `POST /changes`, a proposal, or `GET /changes`, a listing. */
    private suspend fun proposeOrList(operator: Operator, request: ControlHttpRequest): ControlHttpResponse {
        if (request.method == "GET") {
            return listing(operator, request)
        }
        return post(request) {
            val proposal = proposal(it) ?: return@post notFound()
            val change = with(proposal) { plane.propose(operator, key, base, operations, note, confirmations) }
            changeResponse(plane, operator, change, CREATED)
        }
    }

    /**
     * `POST /changes:check` (`CTL-78`): the body of `POST /changes`, read the same way, answered with what the proposal
     * would find, and stored nowhere.
     */
    suspend fun check(operator: Operator, request: ControlHttpRequest): ControlHttpResponse = post(request) {
        val proposal = proposal(it) ?: return@post notFound()
        ChangeCheckAnswer.response(
            with(proposal) { plane.check(operator, key, base, operations, note, confirmations) },
        )
    }

    /** A proposal's body; null for a document this profile serves no write to. */
    private fun proposal(body: JsonNode): Proposal? {
        val key = documentKey(body.text("document"))
        if (!profile.admitsWrite(key)) {
            return null
        }
        val operations = body.array("operations").map { op ->
            decoded("operations") { ChangeRequestCodec.operation(key, op) }
        }
        // A change's kind is its entry point's, never the request's (review K1): break-glass is /break-glass.
        requireRequest(
            !body.has("kind")
        ) { "a change has no kind: propose it here, or request break-glass at /break-glass" }
        return Proposal(key, body.long("base"), operations, body.optText("note").orEmpty(), confirmations(body))
    }

    /** What a proposal's body says: the same for `POST /changes` and its check. */
    private class Proposal(
        val key: SnapshotKey,
        val base: Long,
        val operations: List<Operation>,
        val note: String,
        val confirmations: List<Confirmation>,
    )

    /**
     * `GET /changes?document=<key>` or `?state=<STATE>`, with `after` (the `next` of the page before, a [ListingCursor]
     * token passed back unchanged) and `limit` (1 to
     * [MAX_LIMIT], [DEFAULT_LIMIT] by default): the open changes the caller may see (R2C-4, `CTL-55`), each as a summary
     * of its own, not the stored record (R2C-11).
     */
    private suspend fun listing(operator: Operator, request: ControlHttpRequest): ControlHttpResponse {
        val query = request.query
        val document = query["document"]?.let { decoded("document") { ChangeRequestCodec.documentKeyOf(it) } }
        val state = query["state"]?.let { text ->
            ChangeState.entries.firstOrNull { it.name == text && !it.terminal }
                ?: throw InvalidControlRequestException("state names no state of an open change: $text")
        }
        requireRequest(document != null || state != null) { "a listing names a document or a state" }
        if (!profile.admitsListing(document)) return notFound()
        val limit = query["limit"]?.let { text ->
            text.toIntOrNull()?.takeIf { it in 1..MAX_LIMIT }
                ?: throw InvalidControlRequestException("limit is a number from 1 to $MAX_LIMIT")
        } ?: DEFAULT_LIMIT
        val cursor = if (document != null) ListingCursor.CHANGES_OF_DOCUMENT else ListingCursor.CHANGES_BY_STATE
        val page = plane.changes(
            operator,
            document,
            state,
            query["after"]?.let(cursor::decode),
            limit,
            profile::admitsChange
        )
        return json(
            ControlResponseJson.changes(
                me.ahoo.cosec.control.store.ChangePage(page.changes, page.next?.let(cursor::encode))
            )
        )
    }

    private companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200
    }
}
