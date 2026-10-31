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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.control.authz

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.flat.SubjectAttributes
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.cosec.runtime.RequestSecurity
import me.ahoo.cosec.runtime.document.Actor
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * Who asks the control plane (RFC 0007 §10.1): a principal the host pipeline authenticated, with the memberships and
 * token attributes of that request. [security] is the permitted request itself; [PipelineControlAuthorizer] evaluates
 * the control-plane actions as it, and without it nothing is permitted.
 */
class Operator(
    val actor: Actor,
    val memberships: Memberships,
    /**
     * The token's `attributes` claim, of every JSON type, as the engine holds them: `subject.attributes.<name>` reads
     * each through [SubjectAttributes.string], and so does the grant analysis (RC1-2).
     */
    val attributes: Map<String, Any> = emptyMap(),
    val security: RequestSecurity? = null,
) {
    val tenant: TenantId get() = actor.tenant

    val platform: Boolean get() = actor.tenant == TenantId.PLATFORM

    /**
     * Whether the operator's principal bindings reach it in this request: not with a credential that has a role cap
     * (RFC 0009 §4.2). Without a request, which permits nothing anyway, as a token would.
     */
    val principalBindings: Boolean get() = security?.principal?.principalBindings ?: true

    /** Whether break-glass could reach the operator in this request: only with CoSec's own token. */
    val breakGlass: Boolean get() = security?.principal?.breakGlass ?: true

    override fun toString(): String = "Operator(${actor.principal.value}@${actor.tenant.value})"

    companion object {
        /** The operator of a request the host pipeline permitted; null for an anonymous one. */
        fun of(security: RequestSecurity): Operator? {
            val principal = security.principal ?: return null
            return Operator(
                actor = Actor(principal.tenant, principal.id),
                memberships = security.memberships,
                attributes = principal.attributes,
                security = security,
            )
        }
    }
}

/** A control-plane decision. Only [PERMIT] lets a step through; [INDETERMINATE] is a 503, [DENY] a 403 (RFC 0007 §7.2). */
enum class ControlDecision {
    PERMIT,
    DENY,
    INDETERMINATE,
}

/**
 * Decides one control-plane action (RFC 0007 §7.2): [action] is a `cosec:verb` of [ControlActions], [resource] one of
 * [ControlResources]. An implementation evaluates on documents it has confirmed current, and fails closed.
 */
@InternalCoSecApi
fun interface ControlAuthorizer {
    suspend fun decide(operator: Operator, action: Action, resource: Resource): ControlDecision

    /**
     * [action] on [resource] for [principal] with [memberships], a principal recorded earlier (the approver of a
     * change, RFC 0007 §7.5), asked in [operator]'s request, on the current documents. Indeterminate unless an
     * implementation can check a recorded principal: an approval that cannot be checked again does not count.
     */
    suspend fun decideAs(
        operator: Operator,
        principal: AuthenticatedPrincipal,
        memberships: Memberships,
        action: Action,
        resource: Resource,
    ): ControlDecision = ControlDecision.INDETERMINATE
}

/**
 * Makes the documents the control plane's checker evaluates current before a check (RFC 0007 §7.2): a conditional read
 * of each, so that a just-revoked administrator gets no lag window. The host wires it to the checker's own
 * `SnapshotSource` (one apart from the data plane's); a failure is an indeterminate decision.
 */
@InternalCoSecApi
fun interface DocumentConfirmation {
    suspend fun confirm(keys: Set<SnapshotKey>)

    companion object {
        /** For a checker whose documents are always current, such as an in-memory one. */
        val CURRENT: DocumentConfirmation = DocumentConfirmation { }
    }
}

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
        if (!confirmed(operator.tenant, operator.toString())) {
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
        if (!confirmed(principal.tenant, "${principal.id.value}@${principal.tenant.value}")) {
            return ControlDecision.INDETERMINATE
        }
        return of(checker.decideAs(security, principal, memberships, app, action, resource))
    }

    /** Whether the documents an evaluation for [tenant] reads are confirmed current; a failure is indeterminate. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun confirmed(tenant: TenantId, who: String): Boolean = try {
        confirmation.confirm(setOf(SnapshotKey.Platform, SnapshotKey.Tenant(tenant.value), SnapshotKey.App(app.value)))
        true
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn(e) { "Confirming the documents of $who failed - indeterminate." }
        false
    }

    private fun of(decision: Decision): ControlDecision = when (decision) {
        is Decision.Permit -> ControlDecision.PERMIT
        is Decision.Deny -> ControlDecision.DENY
        is Decision.Indeterminate -> ControlDecision.INDETERMINATE
    }

    companion object {
        private val log = KotlinLogging.logger {}

        /** `cosec.control.app-id` by default. */
        val CONTROL_APP: AppId = AppId("cosec-control")
    }
}

/** The control-plane actions (RFC 0007 §7.1): `cosec:<verb>`. */
object ControlActions {
    const val SERVICE: String = "cosec"
    val READ: Action = Action(SERVICE, "read")
    val SIMULATE: Action = Action(SERVICE, "simulate")
    val EXPLAIN: Action = Action(SERVICE, "explain")
    val ANALYZE: Action = Action(SERVICE, "analyze")
    val SHADOW_READ: Action = Action(SERVICE, "shadow-read")
    val WRITE: Action = Action(SERVICE, "write")
    val GRANT: Action = Action(SERVICE, "grant")
    val PUBLISH: Action = Action(SERVICE, "publish")
    val SKIP_SHADOW: Action = Action(SERVICE, "skip-shadow")
    val APPROVE: Action = Action(SERVICE, "approve")
    val UNPROTECT: Action = Action(SERVICE, "unprotect")
    val BREAKGLASS: Action = Action(SERVICE, "breakglass")

    /** Setting a tenant's boundary or the platform's default (RFC 0007 §7.6); only the platform holds it. */
    val BOUNDARY: Action = Action(SERVICE, "boundary")
}

/**
 * The control-plane resources (RFC 0007 §7.1): `doc/<layer>[/<id>]`, an entry `doc/<layer>[/<id>]/<kind>/<entryId>`
 * a section `doc/<layer>[/<id>]/<name>` (`catalog`, `limits`, `attributes`) and a scope
 * `scope/<tenant>/<app>/<space>`, a wildcard dimension written as the literal segment `*`. The default space, whose id
 * is empty, is written `(default)`: space ids beginning with `(` are CoSec's, as the tenants `(0)` and `(platform)`, so
 * no real space shares the stand-in (RC1-37; [me.ahoo.cosec.model.scope.ScopeResolver.RESERVED_SPACE_PREFIX]).
 */
object ControlResources {
    private const val DOC = "doc"
    private const val SCOPE = "scope"
    const val DEFAULT_SPACE: String = "(default)"

    fun document(key: SnapshotKey): Resource = Resource(DOC, documentId(key), emptyMap())

    /** An entry, or with an empty [id] a section: `doc/app/order/catalog`, `doc/platform/limits`. */
    fun entry(key: SnapshotKey, kind: String, id: String): Resource =
        Resource(DOC, if (id.isEmpty()) "${documentId(key)}/$kind" else "${documentId(key)}/$kind/$id", emptyMap())

    /** [tenant] null: any tenant (a platform `Anyone` binding, an app role binding without a tenant). */
    fun scope(tenant: TenantId?, app: Match<AppId>, space: Match<SpaceId>): Resource {
        val spaceSegment = when (space) {
            Match.Any -> Match.WILDCARD
            is Match.Exactly -> space.value.value.ifEmpty { DEFAULT_SPACE }
        }
        val appSegment = when (app) {
            Match.Any -> Match.WILDCARD
            is Match.Exactly -> app.value.value
        }
        return Resource(
            SCOPE,
            "${segment(tenant?.value ?: Match.WILDCARD)}/${segment(appSegment)}/${segment(spaceSegment)}",
            emptyMap()
        )
    }

    private fun documentId(key: SnapshotKey): String = when (key) {
        SnapshotKey.Platform -> "platform"
        is SnapshotKey.App -> "app/${segment(key.appId)}"
        is SnapshotKey.Tenant -> "tenant/${segment(key.tenantId)}"
    }

    /** An owner or scope part that would add a segment could make one document's resource look like another's. */
    private fun segment(value: String): String {
        require(value.isNotEmpty() && '/' !in value) { "[$value] is not one resource segment." }
        return value
    }
}

/**
 * The rule that never goes through policy (RFC 0007 §7.2, TNT-3): a tenant's principal acts only on its own tenant's
 * document; the platform and app documents are the platform tenant's alone. It is the last line when a policy is
 * written wrongly, so it is checked before any policy is.
 */
internal object TenantIsolation {
    fun allows(operator: Operator, key: SnapshotKey): Boolean = when (key) {
        is SnapshotKey.Tenant -> operator.platform || key.tenantId == operator.tenant.value
        else -> operator.platform
    }
}
