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

package me.ahoo.cosec.runtime

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.MembershipSource
import me.ahoo.cosec.spi.membership.MembershipUnavailableException
import kotlin.coroutines.cancellation.CancellationException

private val log = KotlinLogging.logger {}

/**
 * Answers "may the current principal do this?" in code (RFC 0006 §7.6): in services, message consumers, or to decide
 * which actions a UI shows. The same evaluation as the request, through [pipeline], for the principal and scope of a
 * request the pipeline permitted; no obligation runs and no listener hears of it.
 *
 * Fails closed: only a permit is true. A context the pipeline did not make, an indeterminate decision and an error are
 * false. `cosec-webflux` adds a `Mono<Boolean>` form and `cosec-webmvc` a blocking one.
 */
class AuthorizationChecker(private val pipeline: Pipeline) {
    /**
     * Whether the principal of [security] may perform [action] (`service:verb`, or `http:<METHOD>`) on [resource]:
     * `type/id` (`order/42`), or a path (`/orders/42`) for an `http` resource (RFC 0005 §5.1).
     */
    suspend fun check(security: SecurityContext, action: String, resource: String): Boolean {
        val request = (security as? PipelineSecurityContext)?.security ?: return false
        val (namespace, verb) = action.split(':', limit = 2).takeIf { it.size == 2 } ?: return false
        return check(request, Action(namespace, verb), resource(resource))
    }

    private fun resource(text: String): Resource {
        if (text.startsWith('/')) {
            return Resource(HTTP, text, emptyMap())
        }
        val slash = text.indexOf('/')
        return if (slash < 0) {
            Resource(text, "", emptyMap())
        } else {
            Resource(text.substring(0, slash), text.substring(slash + 1), emptyMap())
        }
    }

    /** As [check], with the action and resource given as the engine reads them. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun check(security: RequestSecurity, action: Action, resource: Resource): Boolean = try {
        pipeline.check(security, action, resource) is Decision.Permit
    } catch (e: CancellationException) {
        if (!currentCoroutineContext().isActive) {
            throw e
        }
        log.warn(e) { "Check of [${action.namespace}:${action.verb} ${resource.id}] failed - denied." }
        false
    } catch (e: Exception) {
        log.warn(e) { "Check of [${action.namespace}:${action.verb} ${resource.id}] failed - denied." }
        false
    }

    /**
     * The decision on [action] over [resource] for the principal of [security] in the default space of [app] of its
     * tenant: how the control plane checks its own actions, in `(cosec.control.app-id, default space)` (RFC 0007 §7.2).
     * Three-valued, so that the caller can tell a deny from an outage; it fails closed like [check]. An anonymous
     * request is denied.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun decide(security: RequestSecurity, app: AppId, action: Action, resource: Resource): Decision {
        val scoped = security.inApp(app) ?: return Decision.Deny(null)
        return try {
            pipeline.check(scoped, action, resource)
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                throw e
            }
            log.warn(e) { "Check of [${action.namespace}:${action.verb} ${resource.id}] failed - indeterminate." }
            Decision.Indeterminate(Cause.SourceUnavailable(DocumentKind.PLATFORM, e))
        } catch (e: Exception) {
            log.warn(e) { "Check of [${action.namespace}:${action.verb} ${resource.id}] failed - indeterminate." }
            Decision.Indeterminate(Cause.SourceUnavailable(DocumentKind.PLATFORM, e))
        }
    }

    /**
     * The decision on [action] over [resource] for [principal] with [memberships], a principal the caller recorded
     * earlier rather than the one [security] authenticated: the approver of a control-plane change, checked again when
     * the change publishes (RFC 0007 §7.5, review K3). As [decide], in the default space of [app] of [principal]'s
     * tenant, on the current documents and in the environment of [security], the request asking now; a scope that
     * does not resolve is a deny.
     *
     * It answers a decision and never a [RequestSecurity] (round-2 review R3): a permitted request is what routes trust,
     * so none is minted for a principal that did not make the request.
     *
     * **Server side only.** [principal] and [memberships] are taken as given, unchecked: pass only what the server
     * itself recorded from an authenticated request (an approval in the control store), never a principal, attribute
     * or membership a caller supplies. Otherwise a caller chooses whom it is checked as.
     *
     * Whether the principal still stands is asked again, though: a principal whose memberships come from the
     * membership provider and which it no longer knows, or knows as deactivated, is denied (RFC 0009 §4.2), and one it
     * cannot answer for is indeterminate. A principal of claimed memberships stands until its token expires.
     */
    suspend fun decideAs(
        security: RequestSecurity,
        principal: AuthenticatedPrincipal,
        memberships: Memberships,
        app: AppId,
        action: Action,
        resource: Resource,
    ): Decision {
        if (!memberships.active) {
            return Decision.Deny(null)
        }
        if (principal.membershipSource is MembershipSource.Provider) {
            val stands = try {
                pipeline.standing(principal)
            } catch (e: MembershipUnavailableException) {
                return Decision.Indeterminate(Cause.MembershipUnavailable(e))
            }
            if (!stands) {
                return Decision.Deny(null)
            }
        }
        val recorded = security.asPrincipal(principal, memberships) ?: return Decision.Deny(null)
        return decide(recorded, app, action, resource)
    }

    private companion object {
        const val HTTP = "http"
    }
}
