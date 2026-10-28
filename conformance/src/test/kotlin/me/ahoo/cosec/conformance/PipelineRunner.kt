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

package me.ahoo.cosec.conformance

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.token.AccessToken
import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.api.token.TokenPrincipal
import me.ahoo.cosec.apikey.ApiKey
import me.ahoo.cosec.apikey.ApiKeyReader
import me.ahoo.cosec.apikey.ApiKeys
import me.ahoo.cosec.apikey.InMemoryApiKeyStore
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.limiter.RateLimitResult
import me.ahoo.cosec.membership.InMemoryMembershipProvider
import me.ahoo.cosec.membership.MembershipClaims
import me.ahoo.cosec.membership.MembershipMarkedPrincipal
import me.ahoo.cosec.membership.MembershipProvider
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.model.condition.IpAddress
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import me.ahoo.cosec.route.RouteMap
import me.ahoo.cosec.route.RouteMapCodec
import me.ahoo.cosec.route.RouteMapException
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.PipelineComponents
import me.ahoo.cosec.runtime.PipelineOptions
import me.ahoo.cosec.runtime.attribute.AttributeDeclaration
import me.ahoo.cosec.runtime.attribute.AttributeProvider
import me.ahoo.cosec.runtime.attribute.AttributeProviders
import me.ahoo.cosec.runtime.attribute.AttributeRequest
import me.ahoo.cosec.runtime.attribute.BlacklistAttributeProvider
import me.ahoo.cosec.runtime.attribute.BlacklistKind
import me.ahoo.cosec.runtime.attribute.BlacklistSource
import me.ahoo.cosec.runtime.attribute.InMemoryBlacklistSource
import me.ahoo.cosec.runtime.authn.ApiKeyAuthenticator
import me.ahoo.cosec.runtime.authn.AuthenticatedPrincipal
import me.ahoo.cosec.runtime.authn.Authenticator
import me.ahoo.cosec.runtime.authn.AuthnResult
import me.ahoo.cosec.runtime.authn.CompositeAuthenticator
import me.ahoo.cosec.runtime.authn.TokenAuthenticator
import me.ahoo.cosec.runtime.event.DecisionEvent
import me.ahoo.cosec.runtime.event.DecisionListener
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.ratelimit.RateLimiter
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.tenant.SimpleTenant
import me.ahoo.cosec.token.RevocationReader
import me.ahoo.cosec.token.SimpleTokenPrincipal
import me.ahoo.cosec.token.TokenExpiredException
import me.ahoo.cosec.token.TokenVerificationException
import me.ahoo.cosec.token.TokenVerifier
import me.ahoo.cosid.StringIdGenerator
import tools.jackson.databind.JsonNode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import me.ahoo.cosec.runtime.Outcome as PipelineOutcome

/**
 * The `pipeline` runner (RFC 0008 §3.3): an HTTP request → [Pipeline] → its outcome, with the scenario's documents
 * compiled as the `engine` runner compiles them and test doubles for every SPI the pipeline calls. The adapters
 * (RFC 0006 PR-7) and listeners (PR-6) are not part of it yet.
 *
 * The pipeline gives the engine an `http` action and resource (`http:<METHOD>` on the path) and the request's host
 * attributes (`http.*` from the headers, `request.attributes.*` from `requestAttributes`), through the pipeline's own
 * reading of [HttpFacts], never the runner's.
 *
 * ```jsonc
 * "documents":          { "platform": {...}, "apps": {...}, "tenants": {...}, "unavailable": ["tenant", ...] },
 * "http":               { "method": "GET", "path": "/orders", "query": "a=b", "headers": { "<name>": "v" | ["v", ...] },
 *                         "hostApp": null, "requestAttributes": { "<name>": "v" } },
 * "authentication":     { "result": "token" | "anonymous" | "authenticated" | "rejected" | "unavailable" | "throws" | "hangs",
 *                         "reason": "TOKEN_EXPIRED", "tenant": "acme", "id": "alice", "roles": [...], "policies": [...],
 *                         "membership": "provider" },
 *                       // "token": the real TokenAuthenticator over a verifier that accepts the request's
 *                       // Authorization header as this token (absent: anonymous) and the scenario's revocations
 *                       "token": { "jti": "t1", "tenant": "acme", "id": "alice", "roles": [...], "policies": [...],
 *                                  "membership": "provider", "issuedAt": "2026-10-01T00:00:00Z" | null,
 *                                  "verification": "valid" | "expired" | "invalid" },
 *                       // "composite": the CompositeAuthenticator, the API key one over "apiKeys" at 2026-11-01
 *                       // ("apiKeysFail": "message" makes the store throw), the token one over "token" if any
 *                       "apiKeys": [{ "key": "k", "id": "billing", "principalId": "svc", "tenantId": "acme", "roles": [...],
 *                                     "policies": [...], "enabled": true, "expiresAt": "..." }],
 * "revocations":        { "tokens": ["t1"], "subjects": [{ "tenant": "acme", "principal": "alice", "issuedBefore": "..." }] }
 *                       | { "fails": "message" },                       // the check reports an error
 * "membershipProvider": { "roles": { "<space>": [...] }, "attachments": [...] } | { "fails": "message" } | { "hangs": true },
 * "snapshot":           { "fails": "message" } | { "hangs": true },      // otherwise every document is installed
 * "options":            { "timeoutMillis": 5000, "authenticationTimeoutMillis": 1000, "membershipTimeoutMillis": 500,
 *                         "attributeTimeoutMillis": 300, "requiredTimeoutMillis": 1000 },
 * // External attributes (RFC 0006 §5.4): providers that declare them, which the documents are compiled against
 * "attributeProviders": [{ "stage": "B", "declares": { "ext.geo.inFence": "bool" | "string" | "stringSet" | "ip" },
 *                          "values": { "ext.geo.inFence": true } | "fails": "message" | "hangs": true }],
 * // The real BlacklistAttributeProvider over an in-memory list holding the request's own principal, address
 * // (10.0.0.1) or device, as named; or over a list that fails
 * "blacklist":          { "blocks": ["principal", "ip", "device"] } | { "fails": "message" },
 * // Decision listeners (RFC 0006 §9): each answers, fails or never answers; REQUIRED ones within
 * // options.requiredTimeoutMillis (default 1000)
 * // A rate limiter (RFC 0006 §8.4) that has permits for every rule but the exhausted ones, or that fails; absent: none
 * "rateLimiter":        { "exhausted": ["rule-id"] } | { "fails": "message" },
 * "listeners":          [{ "name": "audit", "delivery": "required" | "best-effort", "fails": "message" | "hangs": true }],
 * "cancel":             "authentication" | "memberships" | "snapshot"    // the caller goes away while it waits there
 * ```
 *
 * The outcome is `{ "status": 200, "proceed": true }`, `{ "status": 503, "type": "unavailable" }` or, when the caller
 * cancelled, `{ "outcome": "none" }`.
 */
class PipelineRunner(private val compiler: SnapshotCompiler = DocumentSnapshotCompiler) : ScenarioRunner {
    override fun run(scenario: Scenario): Outcome {
        val node = scenario.node
        node.only(
            "\$comment", "rules", "polarity", "level", "description", "documents", "http", "authentication",
            "membershipProvider", "snapshot", "options", "cancel", "expect", "revocations", "attributeProviders",
            "blacklist", "listeners", "rateLimiter", "routes",
        )
        val attributes = AttributeProviders(attributeProviders(node))
        val documents = node.get("documents") ?: EMPTY
        documents.only("platform", "apps", "tenants", "unavailable")
        val snapshot = try {
            compiler(attributes).compile(
                ScenarioDocuments(
                    platform = documents.get("platform"),
                    apps = documents.get("apps")?.fields().orEmpty(),
                    tenants = documents.get("tenants")?.fields().orEmpty(),
                )
            )
        } catch (error: DocumentException) {
            return Outcome.NotCompiled(error.message.orEmpty())
        }
        // The service's route map, loaded as a host loads it: all or nothing (RFC 0005 §5.3).
        val routes = try {
            routes(node.get("routes"))
        } catch (error: RouteMapException) {
            return Outcome.NotCompiled(error.message.orEmpty())
        }
        val cancelAt = node.get("cancel")?.text()?.also {
            if (it !in CANCEL_POINTS) throw ScenarioFormatException("cancel is one of $CANCEL_POINTS, not $it")
        }
        val reached = CompletableDeferred<Unit>()
        fun reach(point: String) {
            if (point == cancelAt) reached.complete(Unit)
        }

        val listeners = listeners(node.get("listeners"), node.get("options") ?: EMPTY)
        val authentication = node.get("authentication") ?: EMPTY
        val provider = node.get("membershipProvider")?.let { provider(authentication, it) { reach("memberships") } }
        val pipeline = Pipeline(
            PipelineComponents(
                authenticator = authenticator(authentication, node.get("revocations")) { reach("authentication") },
                snapshots = SuspendingSnapshotSource(
                    views(snapshot, documents, node.get("snapshot")) { reach("snapshot") }
                ),
                membershipProvider = provider,
                idGenerator = StringIdGenerator { REQUEST_ID },
                options = options(node.get("options") ?: EMPTY),
                attributes = attributes,
                listeners = listeners,
                rateLimiter = rateLimiter(node.get("rateLimiter")),
                routes = routes,
            )
        )
        val facts = facts(node.get("http") ?: EMPTY)
        try {
            if (cancelAt != null) {
                return cancelled(pipeline, facts, reached)
            }
            return answered(runBlocking { pipeline.evaluate(facts) })
        } finally {
            listeners.cancel()
        }
    }

    /** The scenario's route map, loaded as a host loads it; none without one. */
    private fun routes(node: JsonNode?): RouteMap =
        node?.let { RouteMapCodec.load(listOf("scenario" to it.toString().encodeToByteArray())) } ?: RouteMap.EMPTY

    /** The compiler, with the providers' attributes declared when the scenario registers any. */
    private fun compiler(attributes: AttributeProviders): SnapshotCompiler =
        if (compiler === DocumentSnapshotCompiler && !attributes.isEmpty) {
            DocumentSnapshotCompiler.with(attributes.catalog)
        } else {
            compiler
        }

    /** Evaluates, and cancels the caller once the pipeline waits at the scenario's point. */
    private fun cancelled(
        pipeline: Pipeline,
        facts: HttpFacts,
        reached: CompletableDeferred<Unit>
    ): Outcome = runBlocking {
        var outcome: PipelineOutcome? = null
        var cancelled = false
        val caller = launch {
            try {
                outcome = pipeline.evaluate(facts)
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        // A pipeline that answers without reaching the point leaves nothing to cancel; that outcome is reported.
        launch { caller.join().also { reached.complete(Unit) } }
        reached.await()
        caller.cancelAndJoin()
        val answer = outcome
        when {
            answer != null -> answered(answer)
            cancelled -> Outcome.Cancelled
            else -> error("The pipeline neither answered nor was cancelled.")
        }
    }

    private fun answered(outcome: PipelineOutcome): Outcome =
        Outcome.Answered(outcome.status, outcome.proceed, outcome.problem?.type?.slug)

    private fun authenticator(node: JsonNode, revocations: JsonNode?, reached: () -> Unit): Authenticator {
        node.only("result", "reason", "tenant", "id", "roles", "policies", "membership", "token", "apiKeys", "apiKeysFail")
        return when (val result = node.get("result")?.text() ?: "authenticated") {
            "token" -> TokenAuthenticator(ScenarioTokenVerifier(node.required("token")), revocations(revocations))
            "composite" -> CompositeAuthenticator(
                token = node.get("token")?.let { TokenAuthenticator(ScenarioTokenVerifier(it), revocations(revocations)) }
                    ?: Authenticator { AuthnResult.Anonymous },
                apiKey = ApiKeyAuthenticator(apiKeys(node), Clock.fixed(Instant.parse(NOW), ZoneOffset.UTC)),
            )
            "anonymous" -> Authenticator { AuthnResult.Anonymous }
            "authenticated" -> Authenticator { authenticated(node) }
            "rejected" -> Authenticator {
                AuthnResult.Rejected(
                    AuthnResult.RejectReason.valueOf(node.required("reason").text())
                )
            }
            "unavailable" -> Authenticator {
                AuthnResult.Unavailable(
                    IllegalStateException("The scenario's key store is down")
                )
            }
            "throws" -> Authenticator { throw IllegalStateException("The scenario's authenticator throws") }
            "hangs" -> Authenticator {
                reached()
                awaitCancellation()
            }

            else -> throw ScenarioFormatException("Unknown authentication result $result")
        }
    }

    private fun authenticated(node: JsonNode): AuthnResult {
        val memberships = if (node.get("membership")?.text() == MembershipClaims.PROVIDER) {
            null
        } else {
            MembershipClaims.parse(
                node.get("roles")?.strings().orEmpty().toSet(),
                node.get("policies")?.strings().orEmpty().toSet(),
            )
        }
        val principal = AuthenticatedPrincipal(PrincipalId(node.id()), TenantId(node.tenant()), memberships)
        return AuthnResult.Authenticated(principal, "scenario")
    }

    /** The scenario's API keys, by the hash of their `key`; `apiKeysFail` makes every lookup throw. */
    private fun apiKeys(node: JsonNode): ApiKeyReader {
        node.get("apiKeysFail")?.let { fails -> return ApiKeyReader { throw IllegalStateException(fails.text()) } }
        val keys = node.get("apiKeys")?.values().orEmpty().associate { key ->
            key.only("key", "id", "principalId", "tenantId", "roles", "policies", "enabled", "expiresAt")
            ApiKeys.hash(key.required("key").text()) to ApiKey(
                id = key.get("id")?.text() ?: "key",
                principalId = key.required("principalId").text(),
                tenantId = key.get("tenantId")?.text(),
                roles = key.get("roles")?.strings().orEmpty().toSet(),
                policies = key.get("policies")?.strings().orEmpty().toSet(),
                enabled = key.get("enabled")?.bool() ?: true,
                expiresAt = key.get("expiresAt")?.text()?.let(Instant::parse),
            )
        }
        return InMemoryApiKeyStore(keys).reader
    }

    private fun revocations(node: JsonNode?): RevocationReader {
        if (node == null) return RevocationReader.NONE
        node.only("tokens", "subjects", "fails")
        node.get("fails")?.let { fails ->
            return object : RevocationReader {
                override suspend fun isRevoked(tokenId: String): Boolean = throw IllegalStateException(fails.text())

                override suspend fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? =
                    throw IllegalStateException(fails.text())
            }
        }
        val tokens = node.get("tokens")?.strings().orEmpty().toSet()
        val subjects = node.get("subjects")?.values().orEmpty().associate { subject ->
            subject.only("tenant", "principal", "issuedBefore")
            (TenantId(subject.required("tenant").text()) to PrincipalId(subject.required("principal").text())) to
                Instant.parse(subject.required("issuedBefore").text())
        }
        return object : RevocationReader {
            override suspend fun isRevoked(tokenId: String): Boolean = tokenId in tokens

            override suspend fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? =
                subjects[tenant to principal]
        }
    }

    /** Takes whatever token the request presents as the scenario's token, as a verified JWT would read. */
    private class ScenarioTokenVerifier(private val node: JsonNode) : TokenVerifier {
        init {
            node.only("jti", "tenant", "id", "roles", "policies", "membership", "issuedAt", "verification")
        }

        @Suppress("UNCHECKED_CAST")
        override fun <T : TokenPrincipal> verify(accessToken: AccessToken): T {
            when (val verification = node.get("verification")?.text() ?: "valid") {
                "valid" -> Unit
                "expired" -> throw TokenExpiredException("The scenario's token has expired.")
                "invalid" -> throw TokenVerificationException("The scenario's token is invalid.")
                else -> throw ScenarioFormatException("Unknown token verification $verification")
            }
            var principal: CoSecPrincipal = SimplePrincipal(
                node.get("id")?.text() ?: "alice",
                node.get("policies")?.strings().orEmpty().toSet(),
                node.get("roles")?.strings().orEmpty().toSet(),
            )
            node.get("tenant")?.text()?.let { principal = SimpleTenantPrincipal(principal, SimpleTenant(it)) }
            node.get("membership")?.text()?.let { principal = MembershipMarkedPrincipal(principal, it) }
            val issuedAt = node.get("issuedAt")?.textOrNull()?.let(Instant::parse)
            return SimpleTokenPrincipal(node.get("jti")?.text() ?: "t1", principal, issuedAt) as T
        }

        override fun <T : TokenPrincipal> refresh(token: CompositeToken): T = throw UnsupportedOperationException()
    }

    private fun provider(authentication: JsonNode, node: JsonNode, reached: () -> Unit): MembershipProvider {
        node.only("roles", "attachments", "fails", "hangs")
        node.get("fails")?.let { fails ->
            return MembershipProvider { _, _ -> throw IllegalStateException(fails.text()) }
        }
        if (node.get("hangs")?.bool() == true) {
            return MembershipProvider { _, _ ->
                reached()
                awaitCancellation()
            }
        }
        val roles = node.get("roles")?.fields().orEmpty().map { (space, ids) ->
            SpaceId(space) to ids.strings().mapTo(LinkedHashSet(), ::RoleId)
        }.toMap()
        val attachments = node.get("attachments")?.strings().orEmpty().map(::PolicyId)
        return InMemoryMembershipProvider().apply {
            put(
                TenantId(authentication.tenant()),
                PrincipalId(authentication.id()),
                Memberships(Memberships.Source.PROVIDER, roles, attachments),
            )
        }
    }

    /** Every document installed, unless the scenario's snapshot source fails or hangs on load. */
    private fun views(
        snapshot: CompiledSnapshot,
        documents: JsonNode,
        node: JsonNode?,
        reached: () -> Unit
    ): SnapshotViews {
        node?.only("fails", "hangs")
        val unresolved = documents.get("unavailable")?.strings().orEmpty().map {
            Cause.SourceUnavailable(kind(it), IllegalStateException("The scenario makes the $it document unavailable"))
        }

        fun view(key: ViewKey) = SnapshotView(
            snapshot.platform,
            key.app?.let { snapshot.apps[it.value] ?: CompiledDocument.app(it, emptyList(), emptyList()) },
            key.tenant?.let { snapshot.tenants[it.value] ?: CompiledDocument.tenant(it, emptyList(), emptyList()) },
            unresolved,
        )
        val fails = node?.get("fails")?.text()
        val hangs = node?.get("hangs")?.bool() == true
        return object : SnapshotViews {
            override fun peek(key: ViewKey): SnapshotView? = if (fails != null || hangs) null else view(key)

            override fun load(key: ViewKey): CompletableFuture<SnapshotView> {
                reached()
                return when {
                    fails != null -> CompletableFuture.failedFuture(IllegalStateException(fails))
                    hangs -> CompletableFuture()
                    else -> CompletableFuture.completedFuture(view(key))
                }
            }
        }
    }

    private fun options(node: JsonNode): PipelineOptions {
        node.only(
            "timeoutMillis",
            "authenticationTimeoutMillis",
            "membershipTimeoutMillis",
            "attributeTimeoutMillis",
            "requiredTimeoutMillis",
        )
        val defaults = PipelineOptions()
        fun millis(field: String) = node.get(field)?.let { it.asLong().milliseconds }
        return PipelineOptions(
            timeout = millis("timeoutMillis") ?: defaults.timeout,
            authenticationTimeout = millis("authenticationTimeoutMillis") ?: defaults.authenticationTimeout,
            membershipTimeout = millis("membershipTimeoutMillis") ?: defaults.membershipTimeout,
            attributeTimeout = millis("attributeTimeoutMillis") ?: defaults.attributeTimeout,
        )
    }

    private fun listeners(node: JsonNode?, options: JsonNode): DecisionListeners {
        val listeners = node?.values().orEmpty().map { listener ->
            listener.only("name", "delivery", "fails", "hangs")
            val delivery = when (val delivery = listener.get("delivery")?.text() ?: "best-effort") {
                "required" -> Delivery.REQUIRED
                "best-effort" -> Delivery.BEST_EFFORT
                else -> throw ScenarioFormatException("A delivery is required or best-effort, not $delivery")
            }
            val fails = listener.get("fails")?.text()
            val hangs = listener.get("hangs")?.bool() == true
            object : DecisionListener {
                override val name = listener.required("name").text()
                override val delivery = delivery

                override suspend fun onDecision(event: DecisionEvent) {
                    fails?.let { throw IllegalStateException(it) }
                    if (hangs) awaitCancellation()
                }
            }
        }
        val timeout = options.get("requiredTimeoutMillis")?.asLong()?.milliseconds ?: DEFAULT_REQUIRED_TIMEOUT
        return DecisionListeners(listeners, requiredTimeout = timeout)
    }

    private fun rateLimiter(node: JsonNode?): RateLimiter? {
        node ?: return null
        node.only("exhausted", "fails")
        node.get("fails")?.let { fails -> return RateLimiter { _, _, _ -> throw IllegalStateException(fails.text()) } }
        val exhausted = node.get("exhausted")?.strings().orEmpty().toSet()
        return RateLimiter { ruleId, _, limit ->
            val allowed = ruleId !in exhausted
            val perWindow = (limit.permitsPerSecond * limit.windowSeconds).toLong()
            RateLimitResult(allowed, perWindow, if (allowed) perWindow - 1 else 0, java.time.Duration.ofSeconds(1))
        }
    }

    private fun attributeProviders(node: JsonNode): List<AttributeProvider> {
        val providers = node.get("attributeProviders")?.values().orEmpty().map(::attributeProvider)
        val blacklist = node.get("blacklist")?.let { blacklist ->
            blacklist.only("blocks", "fails")
            val source = blacklist.get("fails")?.let { fails ->
                BlacklistSource { _, _ -> throw IllegalStateException(fails.text()) }
            } ?: InMemoryBlacklistSource().apply {
                blacklist.get("blocks")?.strings().orEmpty().forEach { kind ->
                    when (kind) {
                        "principal" -> block(BlacklistKind.PRINCIPAL, BlacklistKind.principal("acme", "alice"))
                        "ip" -> block(BlacklistKind.IP, REMOTE_ADDRESS)
                        "device" -> block(BlacklistKind.DEVICE, DEVICE_ID)
                        else -> throw ScenarioFormatException("A blacklist blocks principal, ip or device, not $kind")
                    }
                }
            }
            BlacklistAttributeProvider(source)
        }
        return providers + listOfNotNull(blacklist)
    }

    /** One provider as the scenario declares it: its values, or a failure, or no answer. */
    private fun attributeProvider(node: JsonNode): AttributeProvider {
        node.only("stage", "declares", "values", "fails", "hangs")
        val stage = AttributeDeclaration.Stage.valueOf(node.get("stage")?.text() ?: "B")
        val declared = node.required("declares").fields().map { (path, type) ->
            val attributeType = ATTRIBUTE_TYPES[type.text()]
                ?: throw ScenarioFormatException("An attribute type is one of ${ATTRIBUTE_TYPES.keys}, not $type")
            AttributeDeclaration(path, attributeType, stage)
        }
        val types = declared.associate { it.path to it.type }
        val values = node.get("values")?.fields().orEmpty().mapValues { (path, value) ->
            val type = types[path] ?: throw ScenarioFormatException("A value for $path, which the provider does not declare")
            attributeValue(type, value)
        }
        val fails = node.get("fails")?.text()
        val hangs = node.get("hangs")?.bool() == true
        return object : AttributeProvider {
            override val attributes = declared

            override suspend fun fetch(request: AttributeRequest, names: Set<String>): Map<String, Any> {
                fails?.let { throw IllegalStateException(it) }
                if (hangs) awaitCancellation()
                return values.filterKeys { it in names }
            }
        }
    }

    /** A value as JSON writes it; the wrong JSON for the declared type is kept, to check it is unavailable. */
    private fun attributeValue(type: AttributeType, value: JsonNode): Any = when {
        value.isBoolean -> value.booleanValue()
        value.isArray -> value.strings().toSet()
        type == AttributeType.IP -> IpAddress.parse(value.text())
            ?: throw ScenarioFormatException("Not an IP address: $value")
        else -> value.text()
    }

    private fun facts(node: JsonNode): HttpFacts {
        node.only("method", "path", "query", "headers", "hostApp", "requestAttributes")
        val headers = node.get("headers")?.fields().orEmpty().mapValues { (_, value) ->
            if (value.isArray) value.strings() else listOf(value.text())
        }
        return HttpFacts(
            method = node.get("method")?.text() ?: "GET",
            rawPath = node.get("path")?.text() ?: "/orders",
            rawQuery = node.get("query")?.textOrNull(),
            headers = Headers.of(headers),
            cookies = Cookies.EMPTY,
            remoteAddress = REMOTE_ADDRESS,
            host = HostScope(node.get("hostApp")?.textOrNull()?.let(::AppId)),
            requestAttributes = node.get("requestAttributes")?.fields().orEmpty().mapValues { (_, value) -> value.text() },
        )
    }

    private fun JsonNode.tenant(): String = get("tenant")?.text() ?: "acme"

    private fun JsonNode.id(): String = get("id")?.text() ?: "alice"

    private companion object {
        const val REQUEST_ID = "scenario-request"
        const val REMOTE_ADDRESS = "10.0.0.1"
        val DEFAULT_REQUIRED_TIMEOUT = 1.seconds

        /** The device a `blacklist` that blocks `device` names; a request sends it as `CoSec-Device-Id`. */
        const val DEVICE_ID = "device-1"
        val ATTRIBUTE_TYPES = mapOf(
            "string" to AttributeType.STRING,
            "bool" to AttributeType.BOOL,
            "stringSet" to AttributeType.STRING_SET,
            "ip" to AttributeType.IP,
        )

        /** The time API keys are judged at, the default request time of the `engine` runner. */
        const val NOW = "2026-11-01T00:00:00Z"
        val CANCEL_POINTS = setOf("authentication", "memberships", "snapshot")
        val EMPTY: JsonNode = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode()

        fun kind(name: String): DocumentKind = when (name) {
            "platform" -> DocumentKind.PLATFORM
            "app" -> DocumentKind.APP
            "tenant" -> DocumentKind.TENANT
            else -> throw ScenarioFormatException("A document is platform, app or tenant, not $name")
        }
    }
}
