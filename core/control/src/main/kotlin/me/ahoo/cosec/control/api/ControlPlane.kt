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

@file:OptIn(ExperimentalCoSecApi::class, InternalCoSecApi::class)

package me.ahoo.cosec.control.api

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.authz.ControlActions
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ApplyMode
import me.ahoo.cosec.control.change.BreakGlass
import me.ahoo.cosec.control.change.CandidateDigest
import me.ahoo.cosec.control.change.ChangeAuthority
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.Changes
import me.ahoo.cosec.control.change.Confirmation
import me.ahoo.cosec.control.change.EntryDigest
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.change.Propagation
import me.ahoo.cosec.control.change.ShadowAck
import me.ahoo.cosec.control.change.requireRequest
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.engine.flat.ViewVersion
import me.ahoo.cosec.engine.pattern.ResourceTarget
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.appliesIn
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.cosec.runtime.document.RoleExpansion
import me.ahoo.cosec.runtime.http.PathNormalizer
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Instant

/** A revision as `history` lists it. */
@InternalCoSecApi
data class RevisionSummary(val revision: Long, val digest: String, val publishedAt: Instant)

/**
 * What changed between two revisions, entry by entry (RFC 0007 §10.1): the history of one policy is a view of it.
 * [policies] and [bindings] are of the expanded documents (RFC 0012 §7.4), so a role's change, and what a catalog that
 * grows gives a `"*"` role, shows as the bindings it derives; [roles] are the roles as written.
 */
@InternalCoSecApi
data class DocumentDiff(
    val from: Long,
    val to: Long,
    val policies: EntryChanges,
    val bindings: EntryChanges,
    val roles: EntryChanges,
    /**
     * Whether anything besides the policies, bindings and roles differs: the catalog, the limits, the attributes, the boundary
     * or the rate limits, compared by their whole content (#1205).
     */
    val sections: Boolean,
)

@InternalCoSecApi
data class EntryChanges(val added: List<String>, val removed: List<String>, val changed: List<String>)

/** A decision on the current heads, and on a change's candidate when one was asked for (RFC 0007 §10.3). */
@InternalCoSecApi
data class Simulation(
    val enforced: DecisionView,
    val enforcedTrace: TraceView?,
    val candidate: DecisionView?,
    val candidateTrace: TraceView?,
    val differs: Boolean,
)

/** A decision at the revisions an audit event recorded, or on the current heads. */
@InternalCoSecApi
data class Explanation(val version: ViewVersion, val decision: DecisionView, val trace: TraceView?)

/** Which bindings could permit, and which DENYs could deny, [action] on [resource] in a scope of [tenant] (§10.3). */
@InternalCoSecApi
data class AccessQuery(
    val tenant: TenantId,
    val app: String?,
    val space: String?,
    val action: Action,
    val resource: Resource
)

/** A binding whose policy has a statement matching the query; its condition, if any, is not evaluated. */
@InternalCoSecApi
data class AccessEntry(
    val document: String,
    val binding: String,
    val subject: String,
    val app: String,
    val space: String,
    val policy: String,
    val statement: Int,
    val conditional: Boolean,
)

/**
 * [allows] and [denies] from the documents the caller may read. [hidden] counts the documents it may not read, and
 * [opaque] the bindings of readable documents whose policy is in one of those: their content is not shown, nor whether
 * they match.
 */
@InternalCoSecApi
data class AccessReport(val allows: List<AccessEntry>, val denies: List<AccessEntry>, val hidden: Int, val opaque: Int)

/**
 * The control plane as a library (RFC 0007 §10.1, Q18). Every call names its [Operator], whom the host pipeline
 * authenticated, and is authorized like a write (RFC 0007 §7.2): the hard rule of tenant isolation first, then the
 * action on each document it touches. Reads need `cosec:read`; `simulate`, `explain` and `analyze` need their own verbs
 * on the simulated tenant's document, and show what they found in other documents only to a caller who may read them.
 */
@InternalCoSecApi
interface ControlPlane {
    suspend fun read(operator: Operator, key: SnapshotKey, revision: Long? = null): PolicyDocument

    suspend fun history(operator: Operator, key: SnapshotKey, before: Long? = null, limit: Int = DEFAULT_LIMIT): List<RevisionSummary>

    suspend fun diff(operator: Operator, key: SnapshotKey, from: Long, to: Long): DocumentDiff

    /**
     * Proposes a change of kind `PUBLISH`, the only kind a caller proposes (review K1): break-glass goes through
     * [breakGlass], a rollback through [rollback].
     */
    suspend fun propose(
        operator: Operator,
        key: SnapshotKey,
        base: Long,
        operations: List<Operation>,
        note: String,
        confirmations: List<Confirmation> = emptyList(),
    ): ChangeRequest

    suspend fun apply(operator: Operator, key: SnapshotKey, desired: PolicyDocument, mode: ApplyMode, note: String): ChangeRequest

    suspend fun rollback(
        operator: Operator,
        key: SnapshotKey,
        toRevision: Long,
        note: String,
        confirmations: List<Confirmation> = emptyList(),
    ): ChangeRequest

    suspend fun rebase(operator: Operator, change: ChangeId): ChangeRequest

    suspend fun startShadow(operator: Operator, change: ChangeId): ChangeRequest

    suspend fun skipShadow(operator: Operator, change: ChangeId, reason: String): ChangeRequest

    suspend fun approve(operator: Operator, change: ChangeId, candidate: String): ChangeRequest

    suspend fun reject(operator: Operator, change: ChangeId, reason: String): ChangeRequest

    suspend fun abandon(operator: Operator, change: ChangeId): ChangeRequest

    suspend fun publish(operator: Operator, change: ChangeId, ack: ShadowAck? = null): ChangeRequest

    suspend fun get(operator: Operator, change: ChangeId): ChangeRequest

    val breakGlass: BreakGlass

    suspend fun simulate(operator: Operator, request: SimulatedRequest, candidate: ChangeId? = null): Simulation

    suspend fun explain(operator: Operator, request: SimulatedRequest, at: ViewVersion? = null): Explanation

    suspend fun analyze(operator: Operator, query: AccessQuery): AccessReport

    suspend fun propagation(operator: Operator, key: SnapshotKey, revision: Long): Propagation

    @InternalCoSecApi
    companion object {
        const val DEFAULT_LIMIT: Int = 20
        const val MAX_LIMIT: Int = 200
    }
}

/** The [ControlPlane] over [changes] and [revisions], authorizing with [authority]. */
@InternalCoSecApi
class DefaultControlPlane(
    private val changes: Changes,
    private val revisions: Revisions,
    private val authority: ChangeAuthority,
    private val simulator: Simulator = Simulator(),
) : ControlPlane {
    override val breakGlass: BreakGlass = BreakGlass(changes, revisions)

    override suspend fun read(operator: Operator, key: SnapshotKey, revision: Long?): PolicyDocument {
        authority.require(operator, key, ControlActions.READ)
        if (revision == null) {
            return revisions.head(key)?.document ?: throw ChangeRejectedException(NOT_FOUND, "[$key] has no document")
        }
        return retained(key, revision)
    }

    override suspend fun history(operator: Operator, key: SnapshotKey, before: Long?, limit: Int): List<RevisionSummary> {
        authority.require(operator, key, ControlActions.READ)
        requireRequest(
            limit in 1..ControlPlane.MAX_LIMIT
        ) { "The limit is 1 to ${ControlPlane.MAX_LIMIT}, not $limit." }
        return revisions.history(
            key
        ).filter { before == null || it.revision < before }.sortedByDescending { it.revision }
            .take(limit).map { RevisionSummary(it.revision, it.digest.toString(), it.publishedAt) }
    }

    override suspend fun diff(operator: Operator, key: SnapshotKey, from: Long, to: Long): DocumentDiff {
        authority.require(operator, key, ControlActions.READ)
        val before = retained(key, from)
        val after = retained(key, to)
        val (kind, owner) = Revisions.ownerOf(key)
        val expandedBefore = RoleExpansion.effective(before)
        val expandedAfter = RoleExpansion.effective(after)
        return DocumentDiff(
            from,
            to,
            changes(
                expandedBefore.policies.associate { it.id.value to EntryDigest.of(it) },
                expandedAfter.policies.associate { it.id.value to EntryDigest.of(it) }
            ),
            changes(
                expandedBefore.bindings.associate { it.id.value to EntryDigest.of(it, kind, owner) },
                expandedAfter.bindings.associate { it.id.value to EntryDigest.of(it, kind, owner) },
            ),
            changes(
                before.roles.associate { it.id.value to EntryDigest.of(it) },
                after.roles.associate { it.id.value to EntryDigest.of(it) },
            ),
            sections(before) != sections(after),
        )
    }

    override suspend fun propose(
        operator: Operator,
        key: SnapshotKey,
        base: Long,
        operations: List<Operation>,
        note: String,
        confirmations: List<Confirmation>,
    ): ChangeRequest = changes.propose(operator, key, base, operations, note, ProvenanceKind.PUBLISH, confirmations)

    override suspend fun apply(operator: Operator, key: SnapshotKey, desired: PolicyDocument, mode: ApplyMode, note: String): ChangeRequest {
        authority.require(operator, key, ControlActions.READ)
        return changes.apply(operator, key, desired, mode, note)
    }

    override suspend fun rollback(
        operator: Operator,
        key: SnapshotKey,
        toRevision: Long,
        note: String,
        confirmations: List<Confirmation>,
    ): ChangeRequest {
        authority.require(operator, key, ControlActions.READ)
        return changes.rollback(operator, key, toRevision, note, confirmations)
    }

    override suspend fun rebase(operator: Operator, change: ChangeId): ChangeRequest = changes.rebase(operator, change)

    override suspend fun startShadow(operator: Operator, change: ChangeId): ChangeRequest = changes.stage(
        operator,
        change
    )

    override suspend fun skipShadow(operator: Operator, change: ChangeId, reason: String): ChangeRequest =
        changes.skipShadow(operator, change, reason)

    override suspend fun approve(operator: Operator, change: ChangeId, candidate: String): ChangeRequest =
        changes.approve(operator, change, candidate)

    override suspend fun reject(operator: Operator, change: ChangeId, reason: String): ChangeRequest =
        changes.reject(operator, change, reason)

    override suspend fun abandon(operator: Operator, change: ChangeId): ChangeRequest = changes.abandon(
        operator,
        change
    )

    override suspend fun publish(operator: Operator, change: ChangeId, ack: ShadowAck?): ChangeRequest =
        changes.publish(operator, change, ack)

    /** A change is read like its document: one the caller may not read is not there (404), so its id tells nothing. */
    override suspend fun get(operator: Operator, change: ChangeId): ChangeRequest = changes.visible(operator, change)

    override suspend fun simulate(operator: Operator, request: SimulatedRequest, candidate: ChangeId?): Simulation {
        val decidable = decidable(operator, request, ControlActions.SIMULATE)
        val tenant = decidable.tenant
        val simulated = decidable.request
        val scope = resolved(simulated) ?: return rejected(simulated)
        // The tenant's document first: with the scope, it decides whether the app's is read, as enforcement decides.
        val tenantHead = held(tenant)
        val keys = simulator.keys(scope, tenantHead?.document)
        val heads = keys.associateWith { if (it == tenant) tenantHead else held(it) }
        val enforced = simulator.decide(simulated, scope, documents(heads))
        val proposed = candidate?.let { id ->
            val change = get(operator, id)
            authority.require(operator, change.key, ControlActions.SIMULATE)
            // What the view read, not what [keys] fetched: an app the tenant binds is not read when it does not compile.
            if (change.key !in enforced.read) {
                throw ChangeRejectedException(
                    BAD_REQUEST,
                    "change ${id.value} is of [${change.key}], which this request does not read"
                )
            }
            val document = changes.candidateOf(id) ?: throw ChangeRejectedException(CONFLICT, "change ${id.value} no longer applies")
            // A tenant change may make the request read another app document, or none (RC1-17): as once it publishes.
            val fetched = if (change.key == tenant) simulator.keys(scope, document) else keys
            // The candidate is no stored content: compiled once, here; the heads come from the cache.
            val proposedHeads = fetched.associateWith { heads[it] ?: held(it) } + (change.key to Held(document, null))
            simulator.decide(simulated, scope, documents(proposedHeads))
        }
        val redaction = redaction(operator, (enforced.read + proposed?.read.orEmpty()).distinct())
        return Simulation(
            redaction.decision(enforced.evaluation.decision),
            redaction.trace(enforced.evaluation),
            proposed?.let { redaction.decision(it.evaluation.decision) },
            proposed?.let { redaction.trace(it.evaluation) },
            proposed != null && differs(enforced.evaluation.decision, proposed.evaluation.decision),
        )
    }

    override suspend fun explain(operator: Operator, request: SimulatedRequest, at: ViewVersion?): Explanation {
        val decidable = decidable(operator, request, ControlActions.EXPLAIN)
        val tenant = decidable.tenant
        val explained = decidable.request
        val scope = resolved(explained)
        if (scope == null) {
            val rejected = rejected(explained)
            return Explanation(at ?: ViewVersion(0, null, 0), rejected.enforced, null)
        }
        // The tenant's document at the revision decided on first: it decides, by enforcement's rule, whether the app's
        // was read (RC1-17), so the app revision an audit event recorded is the one explained.
        suspend fun documentAt(key: SnapshotKey): Held? = if (at == null) {
            held(key)
        } else {
            val revision = when (key) {
                SnapshotKey.Platform -> at.platform
                is SnapshotKey.App -> at.app
                else -> at.tenant
            }
            revision?.takeIf { it > 0 }?.let { retainedHeld(key, it) }
        }
        val tenantDocument = documentAt(tenant)
        val keys = simulator.keys(scope, tenantDocument?.document)
        val documents = keys.associateWith { if (it == tenant) tenantDocument else documentAt(it) }
        // The revisions the view read (R2-5): an app's only when it took the app's document.
        val decided = simulator.decide(explained, scope, documents(documents))
        val redaction = redaction(operator, decided.read)
        return Explanation(
            decided.version,
            redaction.decision(decided.evaluation.decision),
            redaction.trace(decided.evaluation),
        )
    }

    /** A simulated or explained request as enforcement would decide it, and the tenant whose document decides it. */
    private class Decidable(val tenant: SnapshotKey.Tenant, val request: SimulatedRequest)

    /**
     * [request] as enforcement would decide it, once [operator] may [action] on the tenant whose document decides it
     * (RC1-40): the one the scope resolves to, never one the request only names. A request without a principal is
     * decided on tenant `(0)`, so naming another tenant is a 400, after the authorization, as enforcement would decide
     * it on `(0)`. Its resource is the one enforcement would decide on ([enforceable], RC1-41).
     */
    private suspend fun decidable(operator: Operator, request: SimulatedRequest, action: Action): Decidable {
        val tenant = simulator.tenantOf(request)
        val key = SnapshotKey.Tenant(tenant.value)
        authority.require(operator, key, action)
        if (tenant != request.tenant) {
            throw ChangeRejectedException(
                BAD_REQUEST,
                "A request without a principal is decided on tenant ${tenant.value}, as enforcement decides it: " +
                    "it names no other tenant."
            )
        }
        return Decidable(
            key,
            request.copy(action = enforceable(request.action), resource = enforceable(request.resource))
        )
    }

    /** [action] as enforcement would decide it (`PIP-18`): [PathNormalizer.enforceable]. */
    private fun enforceable(action: Action): Action = PathNormalizer.enforceable(action)
        ?: throw ChangeRejectedException(
            BAD_REQUEST,
            "The action is not one enforcement decides: an http method with a lower-case letter is refused as a " +
                "request's is; http:HEAD is decided as http:GET."
        )

    /** [resource] as enforcement would decide it (RC1-41, review L5 of #1200): [PathNormalizer.enforceable]. */
    private fun enforceable(resource: Resource): Resource = PathNormalizer.enforceable(resource)
        ?: throw ChangeRejectedException(
            BAD_REQUEST,
            "The resource is not one enforcement decides: an http path is refused as a request's is (a dot segment, " +
                "an empty segment, an encoded separator, a backslash, a malformed escape or its length), and any " +
                "resource is at most ${PathNormalizer.MAX_PATH_LENGTH} characters, " +
                "${PathNormalizer.MAX_SEGMENT_LENGTH} per segment."
        )

    /** The head of [key] from the cache, decoded and compiled once for its content (RC1-41); null when it has none. */
    private suspend fun held(key: SnapshotKey): Held? = entry(key)?.let { Held(it.document, it) }

    private suspend fun entry(key: SnapshotKey): DocumentCache.Entry? {
        val stored = revisions.stored(key) ?: return null
        return simulator.cache.get(key, stored.digest)
            ?: simulator.cache.put(key, stored.digest, stored.document.bodySize, revisions.decode(key, stored.document))
    }

    /**
     * [key] at [revision], as [retained] reads it: 410 when it is no longer kept. Taken from the cache when it holds that
     * content (the head's, say), never put in it (review M2 of #1240): each retained revision would be an entry of its
     * own, and explaining at revisions is rare, so it is decoded and compiled for the call.
     */
    private suspend fun retainedHeld(key: SnapshotKey, revision: Long): Held {
        val digest = revisions.retainedDigest(key, revision) ?: throw gone(key, revision)
        simulator.cache.get(key, digest)?.let { return Held(it.document, it) }
        val record = revisions.record(key, digest) ?: throw gone(key, revision)
        return Held(revisions.decode(key, record), null)
    }

    override suspend fun analyze(operator: Operator, query: AccessQuery): AccessReport {
        val tenant = SnapshotKey.Tenant(query.tenant.value)
        authority.require(operator, tenant, ControlActions.ANALYZE)
        val resource = enforceable(query.resource)
        // The query's action as enforcement reads it (PIP-18): who may HEAD is who may GET.
        val asked = query.copy(action = enforceable(query.action))
        val app = queriedApp(query)
        val keys = listOfNotNull(SnapshotKey.Platform, tenant, app)
        val readable = keys.filter { it == tenant || authority.holds(operator, it, ControlActions.READ) }
        val platform = SnapshotKey.Platform.takeIf { it in readable }?.let { entry(it) }
        val application = app?.takeIf { it in readable }?.let { entry(it) }
        val found = AccessFindings(asked, ResourceTarget(resource))
        for (key in readable) {
            // The documents and their parsed statements come from the cache simulate and explain read (RC1-41).
            val entry = when (key) {
                SnapshotKey.Platform -> platform
                app -> application
                else -> entry(key)
            } ?: continue
            for (binding in entry.effective.bindings) {
                if (!applies(binding, asked)) {
                    continue
                }
                found.add(key, binding, analyzed(binding, entry, platform, application))
            }
        }
        return AccessReport(found.allows, found.denies, keys.size - readable.size, found.opaque)
    }

    /** The app document [query] names, if any; one no key can name is refused (SCP-10), not echoed: it may hold control characters. */
    private fun queriedApp(query: AccessQuery): SnapshotKey.App? = query.app?.takeIf { it.isNotEmpty() }?.let { app ->
        if (!SnapshotKey.App.isValidAppId(app)) {
            throw ChangeRejectedException(BAD_REQUEST, "The query's app cannot name an app document (SCP-10).")
        }
        SnapshotKey.App(app)
    }

    /** The policy [binding] of [entry] names, as the engine resolves it: its own document, the platform, the app it is scoped to. */
    private fun analyzed(
        binding: Binding,
        entry: DocumentCache.Entry,
        platform: DocumentCache.Entry?,
        app: DocumentCache.Entry?,
    ): DocumentCache.AnalyzedPolicy? = entry.analyzed[binding.policy.value]
        ?: platform?.analyzed?.get(binding.policy.value)
        ?: app?.takeIf { binding.scope.app.exact { id -> id.value } == it.document.owner }?.analyzed?.get(binding.policy.value)

    /** What [analyze] finds, binding by binding: a policy that is not readable counts as opaque. */
    private inner class AccessFindings(private val query: AccessQuery, private val target: ResourceTarget) {
        val allows = ArrayList<AccessEntry>()
        val denies = ArrayList<AccessEntry>()
        var opaque = 0

        fun add(key: SnapshotKey, binding: Binding, policy: DocumentCache.AnalyzedPolicy?) {
            if (policy == null) {
                opaque++
                return
            }
            for (statement in policy.statements) {
                if (!statement.matches(query.action, target)) continue
                val entry = AccessEntry(
                    key.toString(),
                    binding.id.value,
                    subjectOf(binding.subject),
                    binding.scope.app.exact { it.value } ?: WILDCARD,
                    binding.scope.space.exact { it.value } ?: WILDCARD,
                    policy.definition.id.value,
                    statement.index,
                    statement.source.condition != null || policy.definition.condition != null,
                )
                if (statement.source.effect == Effect.DENY) denies += entry else allows += entry
            }
        }
    }

    override suspend fun propagation(operator: Operator, key: SnapshotKey, revision: Long): Propagation {
        authority.require(operator, key, ControlActions.READ)
        return changes.propagation(key, revision)
    }

    private suspend fun retained(key: SnapshotKey, revision: Long): PolicyDocument =
        revisions.revision(key, revision) ?: throw gone(key, revision)

    private fun gone(key: SnapshotKey, revision: Long) = ChangeRejectedException(
        GONE,
        "[$key] keeps no revision $revision"
    )

    private fun resolved(request: SimulatedRequest) = (simulator.scope(request) as? ScopeResolution.Resolved)?.scope

    private fun rejected(request: SimulatedRequest): Simulation {
        val reason = (simulator.scope(request) as ScopeResolution.Rejected).reason
        val view = DecisionView("Rejected", null, reason.name, redacted = false)
        return Simulation(view, null, null, null, differs = false)
    }

    /** The document kinds of [keys] the caller may read; the simulated tenant's needs its own check too. */
    private suspend fun redaction(operator: Operator, keys: List<SnapshotKey>): Redaction = Redaction(
        keys.filter { authority.holds(operator, it, ControlActions.READ) }.map { kindOf(it) }.toSet(),
    )

    private fun documents(heads: Map<SnapshotKey, Held?>) = HeldDocuments(
        heads[SnapshotKey.Platform],
        heads.entries.firstOrNull { it.key is SnapshotKey.App }?.value,
        heads.entries.firstOrNull { it.key is SnapshotKey.Tenant }?.value,
    )

    private fun changes(before: Map<String, String>, after: Map<String, String>) = EntryChanges(
        (after.keys - before.keys).sorted(),
        (before.keys - after.keys).sorted(),
        after.keys.filter { it in before && before[it] != after[it] }.sorted(),
    )

    /** The digest of [document]'s sections: the document encoded with its header fixed and no policies, bindings or roles. */
    private fun sections(document: PolicyDocument): String = CandidateDigest.of(
        PolicyDocument(
            kind = document.kind,
            owner = document.owner,
            revision = 1,
            publishedAt = Instant.EPOCH,
            policies = emptyList(),
            bindings = emptyList(),
            catalog = document.catalog,
            boundary = document.boundary,
            limits = document.limits,
            attributes = document.attributes,
            rateLimits = document.rateLimits,
        )
    )

    /**
     * Whether [binding] can apply to what [query] asks about: in its tenant, as the engine looks a subject up (#1206), and
     * in its app and space.
     */
    private fun applies(binding: Binding, query: AccessQuery): Boolean =
        binding.subject.appliesIn(query.tenant) &&
            inScope(binding.scope.app.exact { it.value }, query.app) &&
            inScope(binding.scope.space.exact { it.value }, query.space)

    /** A binding of [exact] (null: every value) applies to a query of [value] (null: any). */
    private fun inScope(exact: String?, value: String?): Boolean = exact == null || value == null || exact == value

    private fun <T> Match<T>.exact(text: (T) -> String): String? = (this as? Match.Exactly<T>)?.value?.let(text)

    private fun subjectOf(subject: SubjectRef): String = when (subject) {
        SubjectRef.Anyone -> WILDCARD
        is SubjectRef.Principal -> "principal:${subject.tenant.value}/${subject.id.value}"
        is SubjectRef.Role -> "role:${subject.tenant?.value ?: WILDCARD}/${subject.id.value}"
    }

    private fun kindOf(key: SnapshotKey): DocumentKind = when (key) {
        SnapshotKey.Platform -> DocumentKind.PLATFORM
        is SnapshotKey.App -> DocumentKind.APP
        else -> DocumentKind.TENANT
    }

    private companion object {
        const val WILDCARD = Match.WILDCARD
        const val BAD_REQUEST = 400
        const val NOT_FOUND = 404
        const val CONFLICT = 409
        const val GONE = 410
    }
}
