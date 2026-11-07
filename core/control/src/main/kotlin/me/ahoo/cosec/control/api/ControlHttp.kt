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
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ApplyMode
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeRequestCodec
import me.ahoo.cosec.control.change.Confirmation
import me.ahoo.cosec.control.change.InvalidControlRequestException
import me.ahoo.cosec.control.change.ShadowAck
import me.ahoo.cosec.control.change.requireRequest
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.control.store.UnnamedDocumentException
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.ProblemType
import me.ahoo.cosec.runtime.RequestSecurity
import me.ahoo.cosec.runtime.document.PolicyDocumentCodec
import me.ahoo.cosec.runtime.json.CoSecJson
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKeyText
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.time.DateTimeException
import java.time.Instant
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
 * argument is a 400 without detail.
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
@InternalCoSecApi
class ControlHttp(private val plane: ControlPlane) {
    private val reader = CoSecJson.mapper.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)

    @Suppress("TooGenericExceptionCaught")
    suspend fun handle(request: ControlHttpRequest, security: RequestSecurity?): ControlHttpResponse {
        val requestId = security?.requestId ?: ANONYMOUS_REQUEST
        val operator = security?.let(Operator::of) ?: return problem(Problem.UNAUTHORIZED, requestId, null)
        return try {
            route(operator, request)
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
            val unnamed = UnnamedDocumentException.of(e)
            if (unnamed != null) {
                // R3-4 of #1194's review: the record no longer reads under the app id rule; no retry helps, and the answer
                // never echoes the stored id. The log line names it, escaped.
                log.warn { "Control API ${request.method} ${request.path} (request $requestId): ${unnamed.message}" }
                return problem(Problem.CHANGE_UNREADABLE, requestId, CHANGE_UNREADABLE_DETAIL)
            }
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
        UnnamedDocumentException.of(e)?.let { unnamed ->
            log.warn { "Control API ${request.method} ${request.path} (request $requestId): ${unnamed.message}" }
            return problem(Problem.CHANGE_UNREADABLE, requestId, CHANGE_UNREADABLE_DETAIL)
        }
        log.error(
            e
        ) { "Control API ${request.method} ${request.path} (request $requestId): a stored value does not decode." }
        return problem(Problem.INTERNAL_ERROR, requestId, null)
    }

    private suspend fun route(operator: Operator, request: ControlHttpRequest): ControlHttpResponse {
        val segments = request.path.trim('/').split('/').filter { it.isNotEmpty() }
        return when (segments.firstOrNull()) {
            "documents" -> documents(operator, request, segments.drop(1))
            "changes" -> changes(operator, request, segments.drop(1))
            "break-glass" -> breakGlass(operator, request, segments.drop(1))
            "simulate" -> post(
                request
            ) {
                json(
                    simulation(
                        plane.simulate(
                            operator,
                            simulated(it.need("request")),
                            it.optText("candidate")?.let { c -> field("candidate", "a change id") { ChangeId(c) } }
                        )
                    )
                )
            }
            "explain" -> post(
                request
            ) {
                json(
                    explanation(plane.explain(operator, simulated(it.need("request")), it.get("at")?.let(::version)))
                )
            }
            "analyze" -> post(request) { json(report(plane.analyze(operator, query(it.need("query"))))) }
            else -> notFound()
        }
    }

    private suspend fun documents(operator: Operator, request: ControlHttpRequest, segments: List<String>): ControlHttpResponse {
        val (keyText, verb) = segments.firstOrNull()?.let(::verbOf) ?: return notFound()
        val key = documentKey(keyText)
        return when (verb) {
            null -> revisions(operator, request, key, segments.drop(1))
            "apply" -> post(request) {
                val (kind, owner) = Revisions.ownerOf(key)
                val desired = decoded("document") { PolicyDocumentCodec.decode(kind, owner, it.need("document")) }
                val mode = it.text("mode").let { mode ->
                    field("mode", "one of ${ApplyMode.entries.joinToString()}") { ApplyMode.valueOf(mode) }
                }
                changeResponse(plane.apply(operator, key, desired, mode, it.optText("note").orEmpty()), CREATED)
            }

            "rollback" -> post(request) {
                changeResponse(
                    plane.rollback(operator, key, it.long("revision"), it.optText("note").orEmpty()),
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
                    plane.read(operator, key, revisionOf("revision", rest[1]))
                )
                rest.size == 3 && rest[0] == "revisions" && rest[2] == "propagation" -> {
                    val propagation = plane.propagation(operator, key, revisionOf("revision", rest[1]))
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

    private fun history(history: List<RevisionSummary>) = factory.arrayNode().apply {
        history.forEach {
            addObject().put(
                "revision",
                it.revision
            ).put("digest", it.digest).put("publishedAt", it.publishedAt.toString())
        }
    }

    private suspend fun changes(operator: Operator, request: ControlHttpRequest, segments: List<String>): ControlHttpResponse {
        if (segments.isEmpty()) {
            return post(request) {
                val key = documentKey(it.text("document"))
                val operations = it.array("operations").map { op ->
                    decoded("operations") { ChangeRequestCodec.operation(key, op) }
                }
                val confirmations = it.get(
                    "confirmations"
                )?.let { c -> arrayOf("confirmations", c) }?.map(::confirmation).orEmpty()
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
                        confirmations,
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

    private suspend fun breakGlass(operator: Operator, request: ControlHttpRequest, segments: List<String>): ControlHttpResponse = when {
        segments.isEmpty() -> post(request) {
            val change = plane.breakGlass.request(
                operator,
                it.text("principal").let { p -> field("principal", "a principal id") { PrincipalId(p) } },
                instantOf("expiresAt", it.text("expiresAt")),
                it.text("reason"),
                it.optText("policy")?.let { p -> field("policy", "a policy id") { PolicyId(p) } }
                    ?: me.ahoo.cosec.engine.flat.BuiltinPolicies.ROOT,
            )
            changeResponse(change, CREATED)
        }

        segments.size == 1 && request.method == DELETE -> {
            val reason = request.query["reason"] ?: throw InvalidControlRequestException("Revoking break-glass needs a reason.")
            val binding = field("binding", "a binding id") { BindingId(segments[0]) }
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

    private inline fun post(request: ControlHttpRequest, block: (JsonNode) -> ControlHttpResponse): ControlHttpResponse {
        if (request.method != POST) return methodNotAllowed()
        val body = request.body?.takeIf { it.isNotEmpty() }?.let { bytes ->
            try {
                reader.readTree(bytes)
            } catch (e: JacksonException) {
                // Not the parser's message: it is not one of the control plane's own (RC1-39).
                throw InvalidControlRequestException("The request body is not JSON, or holds a key twice.", e)
            }
        } ?: factory.objectNode()
        requireRequest(body.isObject) { "The request body is a JSON object." }
        return block(body)
    }

    private inline fun get(request: ControlHttpRequest, block: () -> ControlHttpResponse): ControlHttpResponse =
        if (request.method == GET) block() else methodNotAllowed()

    /**
     * [change] as the API shows it: the approval names its approver, when and what for, never what the approver held,
     * its token's attributes, which token it was or what that credential could reach (reviews D5, N2). Only the change's
     * record in the store keeps them; a change event carries none of them.
     */
    private fun changeResponse(change: ChangeRequest, status: Int) = ControlHttpResponse(
        status,
        JSON,
        ChangeRequestCodec.encode(
            change.copy(
                approval = change.approval?.copy(
                    memberships = null,
                    attributes = emptyMap(),
                    credentialId = null,
                    issuedAt = null,
                    principalBindings = true,
                    breakGlass = true,
                )
            )
        )
    )

    private fun document(document: me.ahoo.cosec.runtime.document.PolicyDocument) = ControlHttpResponse(
        OK,
        JSON,
        PolicyDocumentCodec.encode(document)
    )

    private fun json(node: JsonNode, status: Int = OK) = ControlHttpResponse(
        status,
        JSON,
        CoSecJson.mapper.writeValueAsBytes(node)
    )

    private fun notFound(): ControlHttpResponse = throw ChangeRejectedException(NOT_FOUND, "no such route")

    private fun methodNotAllowed(): ControlHttpResponse = throw InvalidControlRequestException(
        "This route does not take that method."
    )

    /**
     * A path segment and its custom verb (`c1:publish`, `tenant:acme:apply`): the verb follows the last `:` that is not
     * part of a document key, whose `app:` or `tenant:` prefix has one of its own.
     */
    private fun verbOf(segment: String): Pair<String, String?> {
        val keyed = segment.startsWith("app:") || segment.startsWith("tenant:")
        val separator = segment.indexOf(':', if (keyed) segment.indexOf(':') + 1 else 0)
        return if (separator < 0) segment to null else segment.substring(0, separator) to segment.substring(separator + 1)
    }

    private fun simulated(node: JsonNode): SimulatedRequest {
        node.only(
            "request",
            "tenant",
            "principal",
            "roles",
            "policies",
            "app",
            "space",
            "action",
            "resource",
            "remoteIp",
            "time",
            "headers",
            "requestAttributes",
            "principalBindings",
            "breakGlass",
        )
        return SimulatedRequest(
            tenant = node.text("tenant").let { field("tenant", "a tenant id") { TenantId(it) } },
            principal = node.optText("principal"),
            roles = node.strings("roles"),
            policies = node.strings("policies"),
            app = node.optText("app").orEmpty(),
            space = node.optText("space").orEmpty(),
            action = action(node),
            resource = resource(node.text("resource")),
            remoteIp = node.optText("remoteIp").orEmpty(),
            time = node.optText("time")?.let { instantOf("time", it).toEpochMilli() } ?: System.currentTimeMillis(),
            headers = node.get("headers")?.let { headers ->
                objectOf("headers", headers).properties().associate { (name, value) ->
                    name to if (value.isArray) value.values().map { it.stringIn("headers") } else listOf(value.stringIn("headers"))
                }
            }.orEmpty(),
            requestAttributes = node.get("requestAttributes")?.let { attributes ->
                objectOf("requestAttributes", attributes).properties().associate { (name, value) ->
                    name to value.stringIn("requestAttributes")
                }
            }.orEmpty(),
            principalBindings = node.optBool("principalBindings") ?: true,
            breakGlass = node.optBool("breakGlass") ?: true,
        )
    }

    private fun action(node: JsonNode): Action {
        val action = node.text("action").split(':', limit = 2).takeIf { it.size == 2 }
            ?: throw InvalidControlRequestException("An action is service:verb.")
        return field("action", "service:verb") { Action(action[0], action[1]) }
    }

    private fun resource(text: String): Resource = field("resource", "a resource") {
        if (text.startsWith('/')) return@field Resource("http", text, emptyMap())
        val slash = text.indexOf('/')
        if (slash < 0) {
            Resource(
                text,
                "",
                emptyMap()
            )
        } else {
            Resource(text.substring(0, slash), text.substring(slash + 1), emptyMap())
        }
    }

    private fun query(node: JsonNode): AccessQuery {
        node.only("query", "tenant", "app", "space", "action", "resource")
        return AccessQuery(
            node.text("tenant").let { field("tenant", "a tenant id") { TenantId(it) } },
            node.optText("app"),
            node.optText("space"),
            action(node),
            resource(node.text("resource"))
        )
    }

    private fun version(node: JsonNode): SnapshotVersion {
        node.only("at", "platform", "app", "tenant")
        return SnapshotVersion(node.long("platform"), node.optLong("app"), node.optLong("tenant"))
    }

    private fun confirmation(node: JsonNode): Confirmation {
        node.only("confirmations", "policy", "statement", "digest", "kind")
        val policy = node.text("policy")
        val statement = node.long("statement")
        return field("confirmations", "a confirmation") {
            Confirmation(
                PolicyId(policy),
                Math.toIntExact(statement),
                node.text("digest"),
                node.optText("kind") ?: Confirmation.DENY_NOMATCH
            )
        }
    }

    private fun ack(node: JsonNode): ShadowAck {
        node.only("ack", "window", "counts")
        return field("ack", "a shadow window's acknowledgement") {
            ShadowAck(
                node.text("window"),
                objectOf("counts", node.need("counts")).properties().associate { (outcome, count) ->
                    outcome to count.longIn("counts")
                }
            )
        }
    }

    /** `platform`, `app:<id>` or `tenant:<id>`; the text is not echoed, as it may hold control characters. */
    private fun documentKey(text: String): SnapshotKey = try {
        ChangeRequestCodec.documentKeyOf(text)
    } catch (e: SnapshotFormatException) {
        throw InvalidControlRequestException("That names no document: platform, app:<id> or tenant:<id>.", e)
    }

    private fun changeIdOf(text: String): ChangeId = field("change", "a change id") { ChangeId(text) }

    private fun ControlHttpRequest.revision(name: String): Long =
        query[name]?.let { revisionOf(name, it) } ?: throw InvalidControlRequestException("[$name] is a revision number.")

    private fun revisionOf(name: String, text: String): Long =
        text.toLongOrNull() ?: throw InvalidControlRequestException("[$name] is a revision number.")

    private fun instantOf(name: String, text: String): Instant =
        field(name, "an ISO-8601 instant such as 2026-01-01T00:00:00Z") { Instant.parse(text) }

    /**
     * [decode] of the client's own [name]: the codec's format errors describe what the caller sent, by its JSON path, so
     * they are the request's validation and say so (RC1-39).
     */
    private inline fun <T> decoded(name: String, decode: () -> T): T = try {
        decode()
    } catch (e: SnapshotFormatException) {
        throw InvalidControlRequestException("[$name]: ${e.message}", e)
    }

    /**
     * [parse] of the client's field [name]. What it throws is not shown, being no message of the control plane's own:
     * only that [name] is not [what] (RC1-39), unless it is one of those checks.
     */
    private inline fun <T> field(name: String, what: String, parse: () -> T): T = try {
        parse()
    } catch (e: InvalidControlRequestException) {
        throw e
    } catch (e: IllegalArgumentException) {
        throw InvalidControlRequestException("[$name] is not $what.", e)
    } catch (e: ArithmeticException) {
        throw InvalidControlRequestException("[$name] is not $what.", e)
    } catch (e: DateTimeException) {
        throw InvalidControlRequestException("[$name] is not $what.", e)
    }

    private fun simulation(simulation: Simulation): ObjectNode = factory.objectNode().apply {
        set("enforced", decision(simulation.enforced, simulation.enforcedTrace))
        simulation.candidate?.let { set("candidate", decision(it, simulation.candidateTrace)) }
        put("differs", simulation.differs)
    }

    private fun explanation(explanation: Explanation): ObjectNode = factory.objectNode().apply {
        putObject(
            "version"
        ).put(
            "platform",
            explanation.version.platform
        ).put("app", explanation.version.app).put("tenant", explanation.version.tenant)
        set("decision", decision(explanation.decision, explanation.trace))
    }

    private fun decision(decision: DecisionView, trace: TraceView?): ObjectNode = factory.objectNode().apply {
        put("decision", decision.decision)
        decision.by?.let { set("by", statement(it)) }
        decision.cause?.let { put("cause", it) }
        if (decision.redacted) put("redacted", true)
        trace?.let { t ->
            val node = putObject("trace").put("redacted", t.redacted)
            node.putArray("candidates").apply {
                t.entries.forEach {
                    addObject().put(
                        "effect",
                        it.effect
                    ).put("outcome", it.outcome).set("statement", statement(it.statement))
                }
            }
        }
    }

    private fun statement(statement: StatementView): ObjectNode = factory.objectNode()
        .put("policy", statement.policy).put("statement", statement.statement).put("name", statement.name)
        .put("via", statement.via).put("document", statement.document)

    private fun diff(diff: DocumentDiff): ObjectNode = factory.objectNode().apply {
        put("from", diff.from)
        put("to", diff.to)
        set("policies", entries(diff.policies))
        set("bindings", entries(diff.bindings))
        put("sections", diff.sections)
    }

    private fun entries(changes: EntryChanges): ObjectNode = factory.objectNode().apply {
        putArray("added").apply { changes.added.forEach(::add) }
        putArray("removed").apply { changes.removed.forEach(::add) }
        putArray("changed").apply { changes.changed.forEach(::add) }
    }

    private fun report(report: AccessReport): ObjectNode = factory.objectNode().apply {
        set("allows", accessEntries(report.allows))
        set("denies", accessEntries(report.denies))
        put("hidden", report.hidden)
        put("opaque", report.opaque)
    }

    private fun accessEntries(entries: List<AccessEntry>) = factory.arrayNode().apply {
        entries.forEach {
            addObject().put(
                "document",
                it.document
            ).put("binding", it.binding).put("subject", it.subject).put("app", it.app)
                .put(
                    "space",
                    it.space
                ).put("policy", it.policy).put("statement", it.statement).put("conditional", it.conditional)
        }
    }

    /** The problems of the control API: RFC 0006's where it has one, the control API's own statuses otherwise. */
    private enum class Problem(val slug: String, val title: String, val status: Int, val detailed: Boolean) {
        INVALID_REQUEST(ProblemType.INVALID_REQUEST, detailed = true),
        UNAUTHORIZED(ProblemType.UNAUTHORIZED, detailed = false),
        FORBIDDEN(ProblemType.FORBIDDEN, detailed = false),
        NOT_FOUND("not-found", "Not Found", 404, detailed = false),
        CONFLICT("conflict", "Conflict", 409, detailed = true),

        /** A stored change of an app no document key can name (SCP-7): after [CONFLICT], so [of] never picks it. */
        CHANGE_UNREADABLE("change-unreadable", "Change Unreadable", 409, detailed = true),
        GONE("gone", "Gone", 410, detailed = true),
        PRECONDITION_FAILED("precondition-failed", "Precondition Failed", 412, detailed = true),
        UNPROCESSABLE("unprocessable", "Unprocessable Content", 422, detailed = true),
        PRECONDITION_REQUIRED("precondition-required", "Precondition Required", 428, detailed = true),
        UNAVAILABLE(ProblemType.UNAVAILABLE, detailed = false),
        INTERNAL_ERROR(ProblemType.INTERNAL_ERROR, detailed = false),
        ;

        constructor(type: ProblemType, detailed: Boolean) : this(type.slug, type.title, type.status, detailed)

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

    private fun JsonNode.need(name: String): JsonNode = get(name)?.takeUnless {
        it.isNull
    } ?: throw InvalidControlRequestException("[$name] is missing.")

    /** The string field [name], required. */
    private fun JsonNode.text(name: String): String = need(name).stringIn(name)

    /** The string field [name], or null when it is absent. */
    private fun JsonNode.optText(name: String): String? = get(name)?.stringIn(name)

    private fun JsonNode.long(name: String): Long = need(name).longIn(name)

    private fun JsonNode.optLong(name: String): Long? = get(name)?.longIn(name)

    private fun JsonNode.optBool(name: String): Boolean? = get(name)?.let {
        requireRequest(it.isBoolean) { "[$name] is not a boolean." }
        it.asBoolean()
    }

    private fun JsonNode.strings(name: String): List<String> =
        get(name)?.let { arrayOf(name, it) }?.map { it.stringIn(name) }.orEmpty()

    private fun JsonNode.array(name: String): List<JsonNode> = arrayOf(name, need(name))

    private fun arrayOf(name: String, node: JsonNode): List<JsonNode> {
        requireRequest(node.isArray) { "[$name] is not an array." }
        return node.values().toList()
    }

    private fun objectOf(name: String, node: JsonNode): JsonNode {
        requireRequest(node.isObject) { "[$name] is not an object." }
        return node
    }

    /** This value, of the field [name], as a string. */
    private fun JsonNode.stringIn(name: String): String {
        requireRequest(isString) { "[$name] is not a string." }
        return asString()
    }

    /** This value, of the field [name], as an integer. */
    private fun JsonNode.longIn(name: String): Long {
        requireRequest(isIntegralNumber && canConvertToLong()) { "[$name] is not an integer." }
        return asLong()
    }

    /** This object, the field [name], has only [names]. */
    private fun JsonNode.only(name: String, vararg names: String) {
        requireRequest(isObject) { "[$name] is not an object." }
        val unknown = properties().map { it.key }.toSet() - names.toSet()
        requireRequest(unknown.isEmpty()) { "Unknown fields $unknown in [$name]." }
    }

    companion object {
        private const val CHANGE_UNREADABLE_DETAIL = "This change can no longer be read: it is of an app that cannot be " +
            "an app id under the current rule (SCP-7), so it can be neither shown nor abandoned. An operator removes its " +
            "record from the control store."

        const val JSON: String = "application/json"
        const val PROBLEM_JSON: String = "application/problem+json"
        private const val GET = "GET"
        private const val POST = "POST"
        private const val DELETE = "DELETE"
        private const val OK = 200
        private const val CREATED = 201
        private const val NOT_FOUND = 404
        private const val CONFLICT = 409
        private const val ANONYMOUS_REQUEST = "-"
        private val factory = JsonNodeFactory.instance
    }
}
