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

@file:OptIn(ExperimentalCoSecApi::class)

package me.ahoo.cosec.control.api

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.control.authz.ControlActions
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ApplyMode
import me.ahoo.cosec.control.change.BreakGlass
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
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.engine.pattern.ActionPattern
import me.ahoo.cosec.engine.pattern.ResourcePattern
import me.ahoo.cosec.engine.pattern.ResourceTarget
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.runtime.document.PolicyDefinition
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Instant

/** A revision as `history` lists it. */
data class RevisionSummary(val revision: Long, val digest: String, val publishedAt: Instant)

/** What changed between two revisions, entry by entry (RFC 0007 §10.1): the history of one policy is a view of it. */
data class DocumentDiff(
    val from: Long,
    val to: Long,
    val policies: EntryChanges,
    val bindings: EntryChanges,
    /** Whether the sections (catalog, limits, attributes, rate limits) differ. */
    val sections: Boolean,
)

data class EntryChanges(val added: List<String>, val removed: List<String>, val changed: List<String>)

/** A decision on the current heads, and on a change's candidate when one was asked for (RFC 0007 §10.3). */
data class Simulation(
    val enforced: DecisionView,
    val enforcedTrace: TraceView?,
    val candidate: DecisionView?,
    val candidateTrace: TraceView?,
    val differs: Boolean,
)

/** A decision at the revisions an audit event recorded, or on the current heads. */
data class Explanation(val version: SnapshotVersion, val decision: DecisionView, val trace: TraceView?)

/** Which bindings could permit, and which DENYs could deny, [action] on [resource] in a scope of [tenant] (§10.3). */
@ExperimentalCoSecApi
data class AccessQuery(
    val tenant: TenantId,
    val app: String?,
    val space: String?,
    val action: Action,
    val resource: Resource
)

/** A binding whose policy has a statement matching the query; its condition, if any, is not evaluated. */
@ExperimentalCoSecApi
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
@ExperimentalCoSecApi
data class AccessReport(val allows: List<AccessEntry>, val denies: List<AccessEntry>, val hidden: Int, val opaque: Int)

/**
 * The control plane as a library (RFC 0007 §10.1, Q18). Every call names its [Operator], whom the host pipeline
 * authenticated, and is authorized like a write (RFC 0007 §7.2): the hard rule of tenant isolation first, then the
 * action on each document it touches. Reads need `cosec:read`; `simulate`, `explain` and `analyze` need their own verbs
 * on the simulated tenant's document, and show what they found in other documents only to a caller who may read them.
 */
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

    suspend fun rollback(operator: Operator, key: SnapshotKey, toRevision: Long, note: String): ChangeRequest

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

    suspend fun explain(operator: Operator, request: SimulatedRequest, at: SnapshotVersion? = null): Explanation

    @ExperimentalCoSecApi
    suspend fun analyze(operator: Operator, query: AccessQuery): AccessReport

    suspend fun propagation(operator: Operator, key: SnapshotKey, revision: Long): Propagation

    companion object {
        const val DEFAULT_LIMIT: Int = 20
        const val MAX_LIMIT: Int = 200
    }
}

/** The [ControlPlane] over [changes] and [revisions], authorizing with [authority]. */
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
        require(limit in 1..ControlPlane.MAX_LIMIT) { "The limit is 1 to ${ControlPlane.MAX_LIMIT}, not $limit." }
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
        return DocumentDiff(
            from,
            to,
            changes(
                before.policies.associate { it.id.value to EntryDigest.of(it) },
                after.policies.associate { it.id.value to EntryDigest.of(it) }
            ),
            changes(
                before.bindings.associate { it.id.value to EntryDigest.of(it, kind, owner) },
                after.bindings.associate { it.id.value to EntryDigest.of(it, kind, owner) },
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

    override suspend fun rollback(operator: Operator, key: SnapshotKey, toRevision: Long, note: String): ChangeRequest {
        authority.require(operator, key, ControlActions.READ)
        return changes.rollback(operator, key, toRevision, note)
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
        val tenant = SnapshotKey.Tenant(request.tenant.value)
        authority.require(operator, tenant, ControlActions.SIMULATE)
        val scope = resolved(request) ?: return rejected(request)
        val keys = simulator.keys(scope)
        val heads = keys.associateWith { revisions.head(it)?.document }
        val redaction = redaction(operator, keys)
        val enforced = simulator.evaluate(request, scope, documents(heads))
        val proposed = candidate?.let { id ->
            val change = get(operator, id)
            authority.require(operator, change.key, ControlActions.SIMULATE)
            if (change.key !in keys) {
                throw ChangeRejectedException(
                    BAD_REQUEST,
                    "change ${id.value} is of [${change.key}], which this request does not read"
                )
            }
            val document = changes.candidateOf(id) ?: throw ChangeRejectedException(CONFLICT, "change ${id.value} no longer applies")
            simulator.evaluate(request, scope, documents(heads + (change.key to document)))
        }
        return Simulation(
            redaction.decision(enforced.decision),
            redaction.trace(enforced),
            proposed?.let { redaction.decision(it.decision) },
            proposed?.let { redaction.trace(it) },
            proposed != null && proposed.decision != enforced.decision,
        )
    }

    override suspend fun explain(operator: Operator, request: SimulatedRequest, at: SnapshotVersion?): Explanation {
        val tenant = SnapshotKey.Tenant(request.tenant.value)
        authority.require(operator, tenant, ControlActions.EXPLAIN)
        val scope = resolved(request)
        if (scope == null) {
            val rejected = rejected(request)
            return Explanation(at ?: SnapshotVersion(0, null, 0), rejected.enforced, null)
        }
        val keys = simulator.keys(scope)
        val documents = if (at == null) {
            keys.associateWith { revisions.head(it)?.document }
        } else {
            keys.associateWith { key ->
                val revision = when (key) {
                    SnapshotKey.Platform -> at.platform
                    is SnapshotKey.App -> at.app
                    else -> at.tenant
                }
                revision?.takeIf { it > 0 }?.let { retained(key, it) }
            }
        }
        val evaluation = simulator.evaluate(request, scope, documents(documents))
        val redaction = redaction(operator, keys)
        val version = SnapshotVersion(
            documents[SnapshotKey.Platform]?.revision ?: 0,
            keys.firstOrNull { it is SnapshotKey.App }?.let { documents[it]?.revision ?: 0 },
            documents[tenant]?.revision ?: 0,
        )
        return Explanation(version, redaction.decision(evaluation.decision), redaction.trace(evaluation))
    }

    @ExperimentalCoSecApi
    override suspend fun analyze(operator: Operator, query: AccessQuery): AccessReport {
        val tenant = SnapshotKey.Tenant(query.tenant.value)
        authority.require(operator, tenant, ControlActions.ANALYZE)
        val keys = listOfNotNull(
            SnapshotKey.Platform,
            tenant,
            query.app?.takeIf {
                it.isNotEmpty()
            }?.let { SnapshotKey.App(it) }
        )
        val readable = keys.filter { it == tenant || authority.holds(operator, it, ControlActions.READ) }
        val platform = SnapshotKey.Platform.takeIf { it in readable }?.let { revisions.head(it)?.document }
        val found = AccessFindings(query, ResourceTarget(query.resource))
        for (key in readable) {
            val document = revisions.head(key)?.document ?: continue
            for (binding in document.bindings) {
                if (!inScope(
                        binding.scope.app.exact {
                            it.value
                        },
                        query.app
                    ) || !inScope(binding.scope.space.exact { it.value }, query.space)
                ) {
                    continue
                }
                val policy = document.policies.firstOrNull {
                    it.id == binding.policy
                } ?: platform?.policies?.firstOrNull { it.id == binding.policy }
                found.add(key, binding, policy)
            }
        }
        return AccessReport(found.allows, found.denies, keys.size - readable.size, found.opaque)
    }

    /** What [analyze] finds, binding by binding: a policy that is not readable counts as opaque. */
    private inner class AccessFindings(private val query: AccessQuery, private val target: ResourceTarget) {
        val allows = ArrayList<AccessEntry>()
        val denies = ArrayList<AccessEntry>()
        var opaque = 0

        fun add(key: SnapshotKey, binding: Binding, policy: PolicyDefinition?) {
            if (policy == null) {
                opaque++
                return
            }
            policy.statements.forEachIndexed { index, statement ->
                if (!matches(statement, query.action, target)) return@forEachIndexed
                val entry = AccessEntry(
                    key.toString(),
                    binding.id.value,
                    subjectOf(binding.subject),
                    binding.scope.app.exact { it.value } ?: WILDCARD,
                    binding.scope.space.exact { it.value } ?: WILDCARD,
                    policy.id.value,
                    index,
                    statement.condition != null || policy.condition != null,
                )
                if (statement.effect == Effect.DENY) denies += entry else allows += entry
            }
        }
    }

    override suspend fun propagation(operator: Operator, key: SnapshotKey, revision: Long): Propagation {
        authority.require(operator, key, ControlActions.READ)
        return changes.propagation(key, revision)
    }

    private suspend fun retained(key: SnapshotKey, revision: Long): PolicyDocument =
        revisions.revision(key, revision) ?: throw ChangeRejectedException(GONE, "[$key] keeps no revision $revision")

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

    private fun documents(heads: Map<SnapshotKey, PolicyDocument?>) = Documents(
        heads[SnapshotKey.Platform],
        heads.entries.firstOrNull { it.key is SnapshotKey.App }?.value,
        heads.entries.firstOrNull { it.key is SnapshotKey.Tenant }?.value,
    )

    private fun changes(before: Map<String, String>, after: Map<String, String>) = EntryChanges(
        (after.keys - before.keys).sorted(),
        (before.keys - after.keys).sorted(),
        after.keys.filter { it in before && before[it] != after[it] }.sorted(),
    )

    private fun sections(document: PolicyDocument): List<Any?> =
        listOf(
            document.catalog?.let { it.groups.size },
            document.limits?.maxCost,
            document.attributes?.map { it.path },
            document.rateLimits.map { it.id }
        )

    /** A binding of [exact] (null: every value) applies to a query of [value] (null: any). */
    private fun inScope(exact: String?, value: String?): Boolean = exact == null || value == null || exact == value

    private fun <T> Match<T>.exact(text: (T) -> String): String? = (this as? Match.Exactly<T>)?.value?.let(text)

    private fun subjectOf(subject: SubjectRef): String = when (subject) {
        SubjectRef.Anyone -> WILDCARD
        is SubjectRef.Principal -> "principal:${subject.tenant.value}/${subject.id.value}"
        is SubjectRef.Role -> "role:${subject.tenant?.value ?: WILDCARD}/${subject.id.value}"
    }

    private fun matches(statement: StatementSource, action: Action, target: ResourceTarget): Boolean =
        statement.actions.any { ActionPattern.parse(it).matches(action) } &&
            (
                statement.resources.isEmpty() || statement.resources.any {
                    ResourcePattern.parse(it.pattern, it.caseSensitive, it.decodeAndParseSegments).matches(target)
                }
                )

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
