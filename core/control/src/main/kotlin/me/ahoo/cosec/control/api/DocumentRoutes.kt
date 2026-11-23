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
import me.ahoo.cosec.control.api.ControlHttpSupport.get
import me.ahoo.cosec.control.api.ControlHttpSupport.json
import me.ahoo.cosec.control.api.ControlHttpSupport.notFound
import me.ahoo.cosec.control.api.ControlHttpSupport.post
import me.ahoo.cosec.control.api.ControlHttpSupport.postLarge
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
import me.ahoo.cosec.control.authz.TenantIsolation
import me.ahoo.cosec.control.change.ApplyMode
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ChangeRequestCodec
import me.ahoo.cosec.control.change.ControlStatus
import me.ahoo.cosec.control.change.InvalidControlRequestException
import me.ahoo.cosec.control.change.ownerOf
import me.ahoo.cosec.control.change.requireRequest
import me.ahoo.cosec.control.lifecycle.ControlDerived
import me.ahoo.cosec.control.lifecycle.WorkBudget
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import tools.jackson.databind.JsonNode

/** The routes of `/documents` (`CTL-68`, the listing) and `/documents/{key}` (RFC 0007 §10.2): its reads, `:apply` and `:rollback`. */
internal class DocumentRoutes(
    private val plane: ControlPlane,
    private val work: WorkBudget = ControlDerived.workBudget(),
) : RouteHandler {
    override suspend fun handle(
        operator: Operator,
        request: ControlHttpRequest,
        segments: List<String>
    ): ControlHttpResponse {
        if (segments.isEmpty()) return get(request) { listing(operator, request) }
        val (keyText, verb) = segments.first().let(::verbOf)
        // `:apply` and `:rollback` end the path (R2-M1 of #1240's review); the reads match their own exact shapes.
        if (verb != null && segments.size != 1) {
            return notFound()
        }
        val key = documentKey(unescaped(keyText))
        return when (verb) {
            null -> revisions(operator, request, key, segments.drop(1))
            "apply" -> large(operator, request, key) {
                val (desired, mode) = desired(key, it)
                val change = plane.apply(operator, key, desired, mode, it.optText("note").orEmpty())
                changeResponse(plane, operator, change, CREATED)
            }

            // `CTL-78`: the check of an `:apply`, the same body, stored nowhere.
            "check" -> large(operator, request, key) {
                val (desired, mode) = desired(key, it)
                ChangeCheckAnswer.response(
                    plane.apply(operator, key, desired, mode, it.optText("note").orEmpty(), commit = false)
                )
            }

            "rollback" -> post(request) {
                changeResponse(
                    plane,
                    operator,
                    plane.rollback(operator, key, it.long("revision"), it.optText("note").orEmpty(), confirmations(it)),
                    CREATED
                )
            }

            else -> notFound()
        }
    }

    /**
     * A body that carries a document for [key] (`CTL-85`): the hard rule first, before a permit is held or a byte parsed,
     * so only callers of [key]'s tenant, or the platform, hold work for it; then its tree and candidate, held; then read.
     */
    private suspend fun large(
        operator: Operator,
        request: ControlHttpRequest,
        key: SnapshotKey,
        block: suspend (JsonNode) -> ControlHttpResponse,
    ): ControlHttpResponse {
        if (!TenantIsolation.allows(operator, key)) {
            throw ChangeRejectedException(ControlStatus.FORBIDDEN, "$operator may not act on this document")
        }
        return postLarge(request, work, operator.tenant.value) { block(it) }
    }

    /** The document and the mode an `:apply` body states, for [key]. */
    private fun desired(key: SnapshotKey, body: JsonNode): Pair<PolicyDocument, ApplyMode> {
        val (kind, owner) = ownerOf(key)
        val desired = decoded("document") { PolicyDocumentCodec.decode(kind, owner, body.need("document")) }
        val mode = body.text("mode").let { mode ->
            field("mode", "one of ${ApplyMode.entries.joinToString()}") { ApplyMode.valueOf(mode) }
        }
        return desired to mode
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
                    json(ControlResponseJson.propagation(propagation))
                }

                else -> notFound()
            }
        }

    /** `GET /documents?kind=tenant|app&after=&limit=` (`CTL-68`). */
    private suspend fun listing(operator: Operator, request: ControlHttpRequest): ControlHttpResponse {
        val kind = when (request.query["kind"]) {
            "tenant" -> DocumentKind.TENANT
            "app" -> DocumentKind.APP
            else -> throw InvalidControlRequestException("[kind] is tenant or app.")
        }
        val limit = request.query["limit"]?.let { text ->
            text.toIntOrNull() ?: throw InvalidControlRequestException("[limit] is a number from 1 to ${ControlPlane.MAX_LIMIT}.")
        } ?: ControlPlane.DEFAULT_LIMIT
        // The key of the page before's last document, of this kind: a token of another listing or kind is a 400.
        val after = request.query["after"]?.let { token ->
            ListingCursor.DOCUMENTS.decode(token).also {
                requireRequest(ownerOf(ChangeRequestCodec.documentKeyOf(it)).first == kind) {
                    "[after] is not a cursor of this listing: pass back its `next` unchanged."
                }
            }
        }
        val page = plane.listing.list(operator, kind, after, limit)
        return json(
            ControlResponseJson.documents(
                DocumentPage(page.documents, page.next?.let(ListingCursor.DOCUMENTS::encode), page.asOf)
            )
        )
    }
}
