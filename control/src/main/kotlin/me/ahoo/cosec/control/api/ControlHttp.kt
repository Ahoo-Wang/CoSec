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

package me.ahoo.cosec.control.api

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ApplyMode
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeRequestCodec
import me.ahoo.cosec.control.change.Confirmation
import me.ahoo.cosec.control.change.ShadowAck
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.ProblemType
import me.ahoo.cosec.runtime.RequestSecurity
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.snapshot.SnapshotFormatException
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.document.PolicyDocumentCodec
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

/** A control API request (RFC 0007 §10.2), as an adapter reads it: [path] is relative to `cosec.control.http.base-path`. */
class ControlHttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
)

/** What an adapter writes back: [status], `Content-Type` [contentType] and [body]. */
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
 * document, a change or a DENY exists; a 400, 409, 410, 412, 422 or 428 explains the caller's own request.
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
 * A document key is written `platform`, `app:order` or `tenant:acme`.
 */
class ControlHttp(private val plane: ControlPlane) {
    private val reader = CoSecJsonSerializer.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)

    @Suppress("TooGenericExceptionCaught")
    suspend fun handle(request: ControlHttpRequest, security: RequestSecurity?): ControlHttpResponse {
        val requestId = security?.requestId ?: ANONYMOUS_REQUEST
        val operator = security?.let(Operator::of) ?: return problem(Problem.UNAUTHORIZED, requestId, null)
        return try {
            route(operator, request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ChangeRejectedException) {
            problem(Problem.of(e.status), requestId, e.message)
        } catch (e: IllegalArgumentException) {
            problem(Problem.INVALID_REQUEST, requestId, e.message)
        } catch (e: SnapshotFormatException) {
            problem(Problem.INVALID_REQUEST, requestId, e.message)
        } catch (e: JacksonException) {
            problem(Problem.INVALID_REQUEST, requestId, e.originalMessage)
        } catch (e: Exception) {
            log.error(e) { "Control API ${request.method} ${request.path} failed (request $requestId)." }
            problem(Problem.INTERNAL_ERROR, requestId, null)
        }
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
                            it.get("candidate")?.let { c -> ChangeId(c.text()) }
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
        val key = ChangeRequestCodec.documentKeyOf(keyText)
        return when (verb) {
            null -> revisions(operator, request, key, segments.drop(1))
            "apply" -> post(request) {
                val (kind, owner) = Revisions.ownerOf(key)
                val desired = PolicyDocumentCodec.decode(kind, owner, it.need("document"))
                val mode = ApplyMode.valueOf(it.need("mode").text())
                changeResponse(plane.apply(operator, key, desired, mode, it.get("note")?.text().orEmpty()), CREATED)
            }

            "rollback" -> post(request) {
                changeResponse(
                    plane.rollback(operator, key, it.need("revision").long(), it.get("note")?.text().orEmpty()),
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
                    diff(plane.diff(operator, key, request.long("from"), request.long("to")))
                )
                rest == listOf("revisions") -> json(
                    history(
                        plane.history(
                            operator,
                            key,
                            request.query["before"]?.toLong(),
                            request.query["limit"]?.toInt() ?: ControlPlane.DEFAULT_LIMIT
                        )
                    )
                )
                rest.size == 2 && rest[0] == "revisions" -> document(plane.read(operator, key, rest[1].toLong()))
                rest.size == 3 && rest[0] == "revisions" && rest[2] == "propagation" -> {
                    val propagation = plane.propagation(operator, key, rest[1].toLong())
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
                val key = ChangeRequestCodec.documentKeyOf(it.need("document").text())
                val operations = it.need("operations").values().map { op -> ChangeRequestCodec.operation(key, op) }
                val confirmations = it.get("confirmations")?.values()?.map(::confirmation).orEmpty()
                // A change's kind is its entry point's, never the request's (review K1): break-glass is /break-glass.
                require(
                    !it.has("kind")
                ) { "a change has no kind: propose it here, or request break-glass at /break-glass" }
                changeResponse(
                    plane.propose(
                        operator,
                        key,
                        it.need("base").long(),
                        operations,
                        it.get("note")?.text().orEmpty(),
                        confirmations,
                    ),
                    CREATED
                )
            }
        }
        if (segments.size != 1) return notFound()
        val (idText, verb) = verbOf(segments[0])
        val id = ChangeId(idText)
        if (verb == null) {
            return get(request) { changeResponse(plane.get(operator, id), OK) }
        }
        return post(request) {
            val change = when (verb) {
                "rebase" -> plane.rebase(operator, id)
                "shadow" -> plane.startShadow(operator, id)
                "skip-shadow" -> plane.skipShadow(operator, id, it.need("reason").text())
                "approve" -> plane.approve(operator, id, it.need("candidate").text())
                "reject" -> plane.reject(operator, id, it.need("reason").text())
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
                PrincipalId(it.need("principal").text()),
                Instant.parse(it.need("expiresAt").text()),
                it.need("reason").text(),
                it.get("policy")?.let { p -> PolicyId(p.text()) } ?: me.ahoo.cosec.engine.flat.BuiltinPolicies.ROOT,
            )
            changeResponse(change, CREATED)
        }

        segments.size == 1 && request.method == DELETE -> {
            val reason = request.query["reason"] ?: throw IllegalArgumentException("Revoking break-glass needs a reason.")
            changeResponse(plane.breakGlass.revoke(operator, BindingId(segments[0]), reason), OK)
        }

        segments.size == 1 && verbOf(segments[0]).second == "approve" -> post(request) {
            changeResponse(
                plane.approve(operator, ChangeId(verbOf(segments[0]).first), it.need("candidate").text()),
                OK
            )
        }

        else -> notFound()
    }

    private inline fun post(request: ControlHttpRequest, block: (JsonNode) -> ControlHttpResponse): ControlHttpResponse {
        if (request.method != POST) return methodNotAllowed()
        val body = request.body?.takeIf { it.isNotEmpty() }?.let { reader.readTree(it) } ?: factory.objectNode()
        require(body.isObject) { "The request body is a JSON object." }
        return block(body)
    }

    private inline fun get(request: ControlHttpRequest, block: () -> ControlHttpResponse): ControlHttpResponse =
        if (request.method == GET) block() else methodNotAllowed()

    private fun changeResponse(change: ChangeRequest, status: Int) = ControlHttpResponse(
        status,
        JSON,
        ChangeRequestCodec.encode(change)
    )

    private fun document(document: me.ahoo.cosec.snapshot.document.PolicyDocument) = ControlHttpResponse(
        OK,
        JSON,
        PolicyDocumentCodec.encode(document)
    )

    private fun json(node: JsonNode, status: Int = OK) = ControlHttpResponse(
        status,
        JSON,
        CoSecJsonSerializer.writeValueAsBytes(node)
    )

    private fun notFound(): ControlHttpResponse = throw ChangeRejectedException(NOT_FOUND, "no such route")

    private fun methodNotAllowed(): ControlHttpResponse = throw IllegalArgumentException(
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
        node.only("tenant", "principal", "roles", "policies", "app", "space", "action", "resource", "remoteIp", "time")
        val action = node.need("action").text().split(':', limit = 2).takeIf { it.size == 2 }
            ?: throw IllegalArgumentException("An action is service:verb.")
        return SimulatedRequest(
            tenant = TenantId(node.need("tenant").text()),
            principal = node.get("principal")?.text(),
            roles = node.get("roles")?.values()?.map { it.text() }.orEmpty(),
            policies = node.get("policies")?.values()?.map { it.text() }.orEmpty(),
            app = node.get("app")?.text().orEmpty(),
            space = node.get("space")?.text().orEmpty(),
            action = Action(action[0], action[1]),
            resource = resource(node.need("resource").text()),
            remoteIp = node.get("remoteIp")?.text().orEmpty(),
            time = node.get("time")?.let { Instant.parse(it.text()).toEpochMilli() } ?: System.currentTimeMillis(),
        )
    }

    private fun resource(text: String): Resource {
        if (text.startsWith('/')) return Resource("http", text, emptyMap())
        val slash = text.indexOf('/')
        return if (slash < 0) {
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
        node.only("tenant", "app", "space", "action", "resource")
        val action = node.need("action").text().split(':', limit = 2).takeIf { it.size == 2 }
            ?: throw IllegalArgumentException("An action is service:verb.")
        return AccessQuery(
            TenantId(node.need("tenant").text()),
            node.get("app")?.text(),
            node.get("space")?.text(),
            Action(action[0], action[1]),
            resource(node.need("resource").text())
        )
    }

    private fun version(node: JsonNode): SnapshotVersion {
        node.only("platform", "app", "tenant")
        return SnapshotVersion(node.need("platform").long(), node.get("app")?.long(), node.get("tenant")?.long())
    }

    private fun confirmation(node: JsonNode): Confirmation {
        node.only("policy", "statement", "digest", "kind")
        return Confirmation(
            PolicyId(node.need("policy").text()),
            node.need("statement").long().toInt(),
            node.need("digest").text(),
            node.get("kind")?.text() ?: Confirmation.DENY_NOMATCH
        )
    }

    private fun ack(node: JsonNode): ShadowAck {
        node.only("window", "counts")
        return ShadowAck(
            node.need("window").text(),
            node.need("counts").properties().associate { (outcome, count) -> outcome to count.long() }
        )
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
        return ControlHttpResponse(problem.status, PROBLEM_JSON, CoSecJsonSerializer.writeValueAsBytes(node))
    }

    private fun JsonNode.need(name: String): JsonNode = get(name)?.takeUnless {
        it.isNull
    } ?: throw IllegalArgumentException("[$name] is missing.")

    private fun JsonNode.text(): String {
        require(isString) { "$this is not a string." }
        return asString()
    }

    private fun JsonNode.long(): Long {
        require(isIntegralNumber) { "$this is not an integer." }
        return asLong()
    }

    private fun JsonNode.only(vararg names: String) {
        require(isObject) { "$this is not an object." }
        val unknown = properties().map { it.key }.toSet() - names.toSet()
        require(unknown.isEmpty()) { "Unknown fields $unknown." }
    }

    private fun ControlHttpRequest.long(name: String): Long =
        query[name]?.toLongOrNull() ?: throw IllegalArgumentException("[$name] is a revision number.")

    companion object {
        const val JSON: String = "application/json"
        const val PROBLEM_JSON: String = "application/problem+json"
        private const val GET = "GET"
        private const val POST = "POST"
        private const val DELETE = "DELETE"
        private const val OK = 200
        private const val CREATED = 201
        private const val NOT_FOUND = 404
        private const val ANONYMOUS_REQUEST = "-"
        private val factory = JsonNodeFactory.instance
    }
}
