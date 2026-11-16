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

package me.ahoo.cosec.control.lifecycle

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.Escalation
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.grant.Dimension
import me.ahoo.cosec.control.grant.GrantAnalysis
import me.ahoo.cosec.control.grant.GrantAnalyzer
import me.ahoo.cosec.control.grant.GrantChange
import me.ahoo.cosec.control.grant.GrantDocuments
import me.ahoo.cosec.control.grant.Grantor
import me.ahoo.cosec.control.lifecycle.ChangeAuthority.Companion.UNAVAILABLE
import me.ahoo.cosec.control.lifecycle.ControlTargets.entry
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.RoleExpansion
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * What a change cannot be published alone for (RFC 0007 §7.4, §7.5, §8), for [ChangeAuthority.escalations]: the grant
 * analysis of a tenant change, what a platform or app change needs a second person for, and new or changed break-glass
 * bindings. It analyses and decides nothing: every authorization decision is the [ChangeAuthority]'s.
 */
internal class EscalationAnalysis(private val analyzer: GrantAnalyzer, private val controlApp: AppId) {
    fun breakGlass(base: PolicyDocument?, candidate: PolicyDocument, into: MutableList<Escalation>) {
        val before = base?.bindings.orEmpty().associateBy { it.id }
        val digest = { binding: Binding -> EntryDigest.of(binding, candidate.kind, candidate.owner) }
        for (binding in candidate.bindings.filter { it.breakGlass }) {
            val previous = before[binding.id]
            if (previous == null || digest(previous) != digest(binding)) {
                into += Escalation("break-glass binding ${binding.id.value}", BREAK_GLASS, "always two people (RFC 0007 §8)")
            }
        }
    }

    /**
     * The grant analysis of [candidate] replacing [base] as tenant [key], by [operator]; a failure is a 503. It reads
     * every app document there is, not only the apps the change names (RC1-4): an app document binds roles, so the
     * grantor may be denied something in any app. They are listed in the app list ([ChangeChecks.Context.apps]), which
     * [me.ahoo.cosec.control.store.Revisions] keeps before any app head moves. One that cannot be read leaves the
     * grantor's rights in its app unknown; a list that is not to be trusted ([listedApps]) leaves them unknown in every
     * app not named.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun analyze(
        operator: Operator,
        key: SnapshotKey.Tenant,
        base: PolicyDocument?,
        candidate: PolicyDocument,
        documents: ChangeChecks.Context,
        now: Instant,
    ): GrantAnalysis = try {
        val tenant = TenantId(key.tenantId)
        val granted = GrantDocuments.of(candidate)
        val named = (granted.bindings.mapNotNull { (it.scope.app as? Match.Exactly)?.value } + controlApp)
            .toCollection(LinkedHashSet())
        var listed = listedApps(documents)
        val apps = LinkedHashMap<AppId, PolicyDocument?>()
        val unread = HashSet<AppId>()
        for (app in named + listed.orEmpty()) {
            appHead(documents, app)?.let { apps[app] = it.document } ?: run { unread += app }
        }
        // An app the change names has a head the list does not name: the list misses apps, so it says nothing.
        if (listed != null && named.any { it !in listed && apps[it] != null }) {
            untrusted { "The app list misses an app document that exists; every app a grant reaches is unknown." }
            listed = null
        }
        val snapshot = GrantDocuments.snapshot(
            platform = documents.head(SnapshotKey.Platform)?.document,
            apps = apps,
            tenants = mapOf(tenant to base),
            now = now.toEpochMilli(),
            unreadApps = if (listed == null) Dimension.Every() else Dimension.Only(unread),
        )
        val grantor = Grantor(
            operator.tenant,
            operator.actor.principal,
            operator.memberships,
            operator.attributes,
            operator.principalBindings,
        )
        analyzer.analyze(grantor, snapshot, GrantChange(tenant, granted), trustRoot = false)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw ChangeRejectedException(UNAVAILABLE, "the grant analysis of [$key] failed: ${e.message}", e)
    }

    /**
     * Every app document there is, from the app list; `null`, every app unknown, when the list is not there, is not
     * complete, cannot be read, or has an entry that names no app document (RC1-4, SCP-10). Such an entry is never
     * dropped: one the analysis cannot read could be an app document it would miss.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun listedApps(documents: ChangeChecks.Context): Set<AppId>? = try {
        val list = documents.apps()?.value
        when {
            list == null || !list.complete -> null.also {
                untrusted {
                    "The app list is missing or incomplete: run cosec-migrate repair-references; until then every app a " +
                        "grant reaches is unknown."
                }
            }

            list.entries.any { !isAppId(it) } -> null.also {
                untrusted {
                    "The app list has an entry that names no app document: delete the list and run cosec-migrate " +
                        "repair-references; until then every app a grant reaches is unknown."
                }
            }

            else -> list.entries.mapTo(LinkedHashSet(), ::AppId)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: AppListNotReconciledException) {
        // Until this instance has reconciled the list, between two throttled attempts (review D4).
        null.also { untrusted { "${e.message}: every app a grant reaches is unknown until it is." } }
    } catch (e: Exception) {
        log.warn(e) { "The app list could not be read; every app a grant reaches is unknown." }
        null
    }

    /** Whether [entry] names an app document: a valid [SnapshotKey.App] id, which the wildcard never is. */
    private fun isAppId(entry: String): Boolean = SnapshotKey.App.isValidAppId(entry)

    /**
     * A WARN that the app list is not to be trusted, at most once per [UNTRUSTED_WARN_INTERVAL]: until
     * `repair-references` runs, every tenant change would log it.
     */
    private fun untrusted(message: () -> String) {
        if (untrustedWarnings.ready()) {
            log.warn(message)
        }
    }

    private val untrustedWarnings = Throttle(UNTRUSTED_WARN_INTERVAL)

    /** The head of [app]'s document, [AppHead.document] `null` when there is none; `null` when it cannot be read. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun appHead(documents: ChangeChecks.Context, app: AppId): AppHead? = try {
        AppHead(documents.head(SnapshotKey.App(app.value))?.document)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(e) { "The document of app [${app.value}] could not be read; grants that reach it are unknown." }
        null
    }

    private class AppHead(val document: PolicyDocument?)

    /**
     * What a platform or app change needs (RFC 0007 §7.4.1): the analysis reads tenant documents, so one by anyone but
     * the trust root, or by it under four-eyes, needs a second person for whatever it puts, and for each DENY it removes
     * ([removedDenies], `CTL-31`). For an app document, the approver also sees each tenant binding the change widens
     * (RC1-34).
     */
    suspend fun untenanted(
        key: SnapshotKey,
        operations: List<Operation>,
        base: PolicyDocument?,
        candidate: PolicyDocument,
        documents: ChangeChecks.Context,
    ): List<Escalation> {
        val escalations = ArrayList<Escalation>()
        if (operations.any { it !is Operation.RemovePolicy && it !is Operation.RemoveBinding && it !is Operation.RemoveRole }) {
            escalations += Escalation("document $key", NOT_ANALYZED, "only the trust root publishes it alone")
        }
        escalations += removedDenies(key, base, candidate, documents)
        if (key is SnapshotKey.App) {
            escalations += referringBindings(key, base, candidate, documents)
        }
        return escalations
    }

    /**
     * Each DENY of platform or app [key] that [candidate] removes (RFC 0007 §7.4.4, amended on 2026-11-09; `CTL-31`).
     * A tenant may remove its own DENY alone, since the platform's and the apps' DENYs and its boundary still hold over
     * it; nothing holds over these, so removing one widens what every tenant may do, and needs a second person. What the
     * DENY is judged by is [Narrowing]'s: a binding removed is a DENY removed unless its policy is shown to be ALLOWs
     * only. A document that cannot be read is a 503: an approval must not be skipped for what could not be read.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun removedDenies(
        key: SnapshotKey,
        base: PolicyDocument?,
        candidate: PolicyDocument,
        documents: ChangeChecks.Context,
    ): List<Escalation> {
        base ?: return emptyList()
        val removed = try {
            Narrowing.removedDenies(base, candidate) { policy ->
                policyElsewhere(key, policy) { documents.head(it)?.document }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ChangeRejectedException(UNAVAILABLE, "the DENYs [$key] removes could not be read: ${e.message}", e)
        }
        return removed.map {
            Escalation(
                "$it of $key",
                NOT_ANALYZED,
                "it removes a DENY: only the trust root does it alone"
            )
        }
    }

    /**
     * The tenant bindings that a change of app [key] widens (RC1-34): each binding of a tenant document the
     * back-reference index names (RFC 0007 §5.8) to a permission policy whose ALLOWs the candidate adds or widens,
     * scoped to the app (N1). The approver sees whom the change reaches in which tenant, not only the app document. An
     * index or a head that cannot be read is a 503: an approval must not be asked for what cannot be shown.
     */
    // B4: the referrer walk extracts into a function of its own
    @Suppress("NestedBlockDepth", "TooGenericExceptionCaught")
    private suspend fun referringBindings(
        key: SnapshotKey.App,
        base: PolicyDocument?,
        candidate: PolicyDocument,
        documents: ChangeChecks.Context,
    ): List<Escalation> = try {
        val app = AppId(key.appId)
        val widened = analyzer.widened(GrantDocuments.of(base), GrantDocuments.of(candidate))
        val found = ArrayList<Escalation>()
        for ((policy, statements) in widened) {
            val referrers = documents.referrers(key, policy)
            val tenants = referrers.filterIsInstance<SnapshotKey.Tenant>().sortedBy { it.tenantId }
            for (tenant in tenants) {
                val bindings = documents.head(
                    tenant
                )?.document?.let(RoleExpansion::effective)?.bindings.orEmpty().filter {
                    it.policy == policy && it.scope.app == Match.Exactly(app)
                }
                for (binding in bindings) {
                    // Only where the binding is: whom another tenant binds, and in which space, is not the author's.
                    found += Escalation(
                        "binding ${binding.id.value} of [$tenant]",
                        Escalation.TENANT_BINDING,
                        "[${policy.value}] statements $statements widen what this binding grants",
                    )
                }
            }
        }
        found
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw ChangeRejectedException(UNAVAILABLE, "the tenants referring to [$key] could not be read: ${e.message}", e)
    }

    internal companion object {
        private val log = KotlinLogging.logger(ChangeAuthority::class.java.name)
        const val NOT_ANALYZED = "NOT_ANALYZED"
        const val BREAK_GLASS = "BREAK_GLASS"

        /** How often the WARN that the app list is not to be trusted may be logged (RC1-4). */
        val UNTRUSTED_WARN_INTERVAL: Duration = 1.minutes
    }
}
