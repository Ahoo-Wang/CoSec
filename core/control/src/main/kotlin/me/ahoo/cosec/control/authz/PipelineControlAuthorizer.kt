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

package me.ahoo.cosec.control.authz

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import kotlin.coroutines.cancellation.CancellationException

/**
 * The control plane's own checks through the host's engine (RFC 0007 §7.2): the suspending [AuthorizationChecker] of
 * RFC 0006 §7.6, in the default space of [app] (`cosec.control.app-id`), after [confirmation] has confirmed the
 * documents the evaluation reads: the platform's, the operator's tenant's and [app]'s.
 */
@InternalCoSecApi
class PipelineControlAuthorizer(
    private val checker: AuthorizationChecker,
    private val confirmation: DocumentConfirmation,
    private val app: AppId = CONTROL_APP,
) : ControlAuthorizer {
    override suspend fun decide(operator: Operator, action: Action, resource: Resource): ControlDecision {
        val security = operator.security ?: return ControlDecision.DENY
        if (confirmed(operator.tenant, operator.toString()) == null) {
            return ControlDecision.INDETERMINATE
        }
        return of(checker.decide(security, app, action, resource))
    }

    /** As [decide], for [principal] as recorded, through the runtime's own entry point (round-2 review R3). */
    override suspend fun decideAs(
        operator: Operator,
        principal: AuthenticatedPrincipal,
        memberships: Memberships,
        action: Action,
        resource: Resource,
    ): ControlDecision {
        val security = operator.security ?: return ControlDecision.DENY
        if (confirmed(principal.tenant, "${principal.id.value}@${principal.tenant.value}") == null) {
            return ControlDecision.INDETERMINATE
        }
        return of(checker.decideAs(security, principal, memberships, app, action, resource))
    }

    /**
     * One confirmation of the documents for all of [block]'s decisions (#1218), instead of one for each: a change of a
     * thousand bindings asked for two thousand and one. The batch confirms each set of documents the first time a
     * decision needs it and keeps what it found, and once [block] has returned confirms them all again: if every one is
     * the version it first found, no decision rests on a document that moved while they were taken. If one moved,
     * the decisions are taken again from the start on a new confirmation, so a principal revoked meanwhile is denied
     * exactly as it would be by a confirmation before each decision. A [block] that throws is not confirmed again: a
     * refusal, on documents at least as old, is the safe answer. Documents that cannot be confirmed again, or keep
     * moving, are a 503.
     */
    override suspend fun <T> batch(block: suspend (ControlAuthorizer) -> T): T {
        repeat(MAX_BATCHES) {
            val batch = Batch()
            val result = block(batch)
            if (batch.stillCurrent()) {
                return result
            }
        }
        throw ChangeRejectedException(
            UNAVAILABLE,
            "the control documents kept changing while the change was authorized"
        )
    }

    /** Confirms the documents [tenant]'s evaluation reads: the versions found, or null when it failed (indeterminate). */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun confirmed(tenant: TenantId, who: String): Map<SnapshotKey, Any>? = try {
        confirmation.confirm(keysOf(tenant))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(e) { "Confirming the documents of $who failed - indeterminate." }
        null
    }

    private fun keysOf(tenant: TenantId): Set<SnapshotKey> =
        setOf(SnapshotKey.Platform, SnapshotKey.Tenant(tenant.value), SnapshotKey.App(app.value))

    /** Decisions on the documents one confirmation per tenant made current, and what that found, to compare at the end. */
    private inner class Batch : ControlAuthorizer {
        private val lock = Mutex()

        /** The documents confirmed for a tenant, or null for one that could not be: its decisions stay indeterminate. */
        private val confirmedFor = HashMap<TenantId, Boolean>()

        /** The version of each document when the batch first confirmed it. */
        private val first = HashMap<SnapshotKey, Any>()

        override suspend fun decide(operator: Operator, action: Action, resource: Resource): ControlDecision {
            val security = operator.security ?: return ControlDecision.DENY
            if (!confirm(operator.tenant, operator.toString())) {
                return ControlDecision.INDETERMINATE
            }
            return of(checker.decide(security, app, action, resource))
        }

        override suspend fun decideAs(
            operator: Operator,
            principal: AuthenticatedPrincipal,
            memberships: Memberships,
            action: Action,
            resource: Resource,
        ): ControlDecision {
            val security = operator.security ?: return ControlDecision.DENY
            if (!confirm(principal.tenant, "${principal.id.value}@${principal.tenant.value}")) {
                return ControlDecision.INDETERMINATE
            }
            return of(checker.decideAs(security, principal, memberships, app, action, resource))
        }

        /** Nested, a batch is one batch: the outer confirmation covers them. */
        override suspend fun <T> batch(block: suspend (ControlAuthorizer) -> T): T = block(this)

        private suspend fun confirm(tenant: TenantId, who: String): Boolean = lock.withLock {
            confirmedFor.getOrPut(tenant) {
                val versions = confirmed(tenant, who) ?: return@getOrPut false
                versions.forEach { (key, version) -> first.putIfAbsent(key, version) }
                true
            }
        }

        /** Whether every document the batch confirmed is still the version it found; a failure to confirm is a 503. */
        @Suppress("TooGenericExceptionCaught")
        suspend fun stillCurrent(): Boolean {
            val keys = lock.withLock { first.keys.toSet() }
            if (keys.isEmpty()) {
                return true
            }
            val now = try {
                confirmation.confirm(keys)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn(e) { "Confirming the control documents again failed - indeterminate." }
                throw ChangeRejectedException(UNAVAILABLE, "the control documents could not be confirmed again", e)
            }
            return lock.withLock { first.all { (key, version) -> now[key] == version } }
        }
    }

    private fun of(decision: Decision): ControlDecision = when (decision) {
        is Decision.Permit -> ControlDecision.PERMIT
        is Decision.Deny -> ControlDecision.DENY
        is Decision.Indeterminate -> ControlDecision.INDETERMINATE
    }

    @InternalCoSecApi
    companion object {
        private val log = KotlinLogging.logger {}

        /** `cosec.control.app-id` by default. */
        val CONTROL_APP: AppId = AppId("cosec-control")

        /** How often one change's decisions are taken again because a document moved under them. */
        private const val MAX_BATCHES = 4
        private const val UNAVAILABLE = 503
    }
}
