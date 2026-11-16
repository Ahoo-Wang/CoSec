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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.api.ControlHttpSupport.factory
import me.ahoo.cosec.control.api.ControlResponseJson.entries
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.InvalidControlRequestException
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.runtime.http.ProblemType
import me.ahoo.cosec.runtime.http.RequestSecurity
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKeyText
import tools.jackson.core.JacksonException
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

/** A control API request (RFC 0007 §10.2), as an adapter reads it: [path] is relative to `cosec.control.http.base-path`. */
@InternalCoSecApi
class ControlHttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
)

/** What an adapter writes back: [status], `Content-Type` [contentType] and [body]. */
@InternalCoSecApi
class ControlHttpResponse(val status: Int, val contentType: String, val body: ByteArray) {
    override fun toString(): String = "ControlHttpResponse($status, ${body.decodeToString()})"
}

/**
 * The control plane's HTTP API (RFC 0007 §10.2), without a web framework: the starter registers it as functional
 * routes on the host (WebFlux or WebMvc, RFC 0008 S1), behind the host pipeline. Every route takes the
 * [RequestSecurity] the host pipeline permitted: without one (anonymous), it is a 401 and nothing else happens. Every
 * route then goes through [ControlPlane], so it is authorized by `ChangeAuthority`, hard rule first.
 *
 * Errors are RFC 9457 problems in RFC 0006's vocabulary (`invalid-request`, `unauthorized`, `forbidden`,
 * `unavailable`, `internal-error`), plus the control API's own statuses (404, 409, 410, 412, 422, 428). A problem
 * says what the caller can act on: a 401, 403, 404, 500 or 503 carries no detail, so a refusal never tells whether a
 * document, a change or a DENY exists, and why a refused step was refused is logged at INFO instead; a 400, 409, 410,
 * 412, 422 or 428 explains the caller's own request (RC1-39): a 400's detail is the message of one of the control
 * plane's own checks of the request, or names the field that does not parse, never an exception's message or a class
 * name. A stored document or record that does not decode is a 500, logged; anything else refused as an illegal
 * argument is a 400 without detail. A request whose path, query or body holds a string with an unpaired UTF-16
 * surrogate is a 400 before any route reads it (RC1-52, `IDN-10`): every id it names is one of those strings.
 *
 * | Method and path | Call |
 * |---|---|
 * | `GET /documents/{key}`, `GET /documents/{key}/revisions/{rev}` | `read` |
 * | `GET /documents/{key}/revisions?before=&limit=` | `history` |
 * | `GET /documents/{key}/diff?from=&to=` | `diff` |
 * | `GET /documents/{key}/revisions/{rev}/propagation` | `propagation` |
 * | `POST /documents/{key}:apply`, `POST /documents/{key}:rollback` | `apply`, `rollback` |
 * | `POST /changes`, `GET /changes/{id}` | `propose` (201), `get` |
 * | `POST /changes/{id}:rebase`, `:shadow`, `:skip-shadow`, `:approve`, `:reject`, `:abandon`, `:publish` | transitions |
 * | `POST /break-glass` (201), `POST /break-glass/{change}:approve`, `DELETE /break-glass/{bindingId}?reason=` | §8 |
 * | `POST /simulate`, `POST /explain`, `POST /analyze` | §10.3 |
 *
 * A document key is written `platform`, `app:order` or `tenant:acme`. `DELETE /break-glass/{bindingId}` answers 200
 * only when the binding no longer grants on the platform document's head afterwards, whoever removed it; 404 when that
 * head has no binding of that id and no stalled revoke of it that the caller may see holds the slot; 409 when the
 * binding there is not a break-glass binding, or still grants after the revoke, naming the state of the caller's change
 * (`BRK-11`).
 */
// B4: the HTTP layer becomes a route table with one handler per route
@Suppress("TooManyFunctions")
@InternalCoSecApi
class ControlHttp(plane: ControlPlane) {
    private val routes = ControlRoutes(plane)

    @Suppress("TooGenericExceptionCaught")
    suspend fun handle(request: ControlHttpRequest, security: RequestSecurity?): ControlHttpResponse {
        val requestId = security?.requestId ?: ANONYMOUS_REQUEST
        val operator = security?.let(Operator::of) ?: return problem(Problem.UNAUTHORIZED, requestId, null)
        return try {
            routes.route(operator, request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChangeRejectedException) {
            val problem = Problem.of(e.status)
            if (!problem.detailed) {
                // The answer says nothing, so a refusal never tells what exists; the operator's administrator reads why.
                log.info {
                    "Control API ${request.method} ${request.path} by ${operator.tenant.value}/" +
                        "${operator.actor.principal.value} is ${problem.status} " +
                        "(request $requestId): ${e.message}"
                }
            }
            problem(problem, requestId, e.message)
        } catch (e: InvalidControlRequestException) {
            problem(Problem.INVALID_REQUEST, requestId, e.message)
        } catch (e: SnapshotFormatException) {
            stored(request, requestId, e)
        } catch (e: JacksonException) {
            stored(request, requestId, e)
        } catch (e: IllegalArgumentException) {
            // Refused for a reason none of the control plane's own checks gives (RC1-39): not shown, logged.
            log.info {
                "Control API ${request.method} ${request.path} (request $requestId) is 400: " +
                    SnapshotKeyText.printable(e.message.orEmpty())
            }
            problem(Problem.INVALID_REQUEST, requestId, null)
        } catch (e: Exception) {
            log.error(e) { "Control API ${request.method} ${request.path} failed (request $requestId)." }
            problem(Problem.INTERNAL_ERROR, requestId, null)
        }
    }

    /**
     * What the control plane read and could not decode (RC1-39): a stored document or record, perhaps one a newer
     * version wrote during a rolling upgrade. The caller can do nothing about it: a 500 without detail, the error logged
     * with what it names (a document's key).
     */
    private fun stored(request: ControlHttpRequest, requestId: String, e: Exception): ControlHttpResponse {
        log.error(
            e
        ) { "Control API ${request.method} ${request.path} (request $requestId): a stored value does not decode." }
        return problem(Problem.INTERNAL_ERROR, requestId, null)
    }

    /** The problems of the control API: RFC 0006's where it has one, the control API's own statuses otherwise. */
    private enum class Problem(val slug: String, val title: String, val status: Int, val detailed: Boolean) {
        INVALID_REQUEST(ProblemType.INVALID_REQUEST, detailed = true),
        UNAUTHORIZED(ProblemType.UNAUTHORIZED, detailed = false),
        FORBIDDEN(ProblemType.FORBIDDEN, detailed = false),
        NOT_FOUND("not-found", "Not Found", 404, detailed = false),
        CONFLICT("conflict", "Conflict", 409, detailed = true),
        GONE("gone", "Gone", 410, detailed = true),
        PRECONDITION_FAILED("precondition-failed", "Precondition Failed", 412, detailed = true),
        UNPROCESSABLE("unprocessable", "Unprocessable Content", 422, detailed = true),
        PRECONDITION_REQUIRED("precondition-required", "Precondition Required", 428, detailed = true),
        UNAVAILABLE(ProblemType.UNAVAILABLE, detailed = false),
        INTERNAL_ERROR(ProblemType.INTERNAL_ERROR, detailed = false),
        ;

        constructor(type: ProblemType, detailed: Boolean) : this(type.slug, type.title, type.status, detailed)

        @InternalCoSecApi
        companion object {
            fun of(status: Int): Problem = entries.firstOrNull { it.status == status } ?: INTERNAL_ERROR
        }
    }

    private fun problem(problem: Problem, requestId: String, detail: String?): ControlHttpResponse {
        val node = factory.objectNode()
            .put("type", ProblemType.TYPE_PREFIX + problem.slug)
            .put("title", problem.title)
            .put("status", problem.status)
            .put("code", problem.name)
            .put("requestId", requestId)
        if (problem.detailed && !detail.isNullOrBlank()) node.put("detail", detail)
        return ControlHttpResponse(problem.status, PROBLEM_JSON, CoSecJson.mapper.writeValueAsBytes(node))
    }

    @InternalCoSecApi
    companion object {
        const val JSON: String = "application/json"
        const val PROBLEM_JSON: String = "application/problem+json"
        private const val ANONYMOUS_REQUEST = "-"
    }
}
