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

package me.ahoo.cosec.runtime.stage

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.Subject
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.runtime.attribute.FetchedAttributes
import me.ahoo.cosec.runtime.call.Answer
import me.ahoo.cosec.runtime.call.valueOr
import me.ahoo.cosec.runtime.http.Outcome
import me.ahoo.cosec.spi.attribute.AttributeRequest
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.authn.MembershipSource
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.membership.MembershipUnavailableException

/**
 * Stage 3: the principal's memberships (RFC 0004 §4.5), together with the stage-A attributes such as the blacklist
 * (K17): neither waits for the other. A failure is a 503, never "no memberships": no roles would also mean no `inRole`
 * DENY matches (PIP-4). A credential whose principal the membership provider does not know, or knows as not active, is
 * refused (`IDN-3`): the request goes on as anonymous, with the credential rejected, as if the authenticator had
 * rejected it (RFC 0006 K2), and stage A is asked again for it.
 */
internal class ResolveMembers(
    private val fetching: AttributeFetching,
    private val memberships: MembershipResolver,
) : Stage {
    override suspend fun run(state: RequestState): Outcome? {
        while (true) {
            val fetched = fetching.fetched()
            state.fetched = fetched
            val principal = state.principal
            val subject = subject(principal)
            val stageA = if (fetched === FetchedAttributes.NONE || fetching.attributes.stageA.isEmpty()) {
                null
            } else {
                AttributeRequest(
                    state.tenant,
                    subject,
                    null,
                    state.action,
                    state.resource,
                    state.facts.remoteAddress,
                    state.requestId,
                    state.parameters.deviceId,
                )
            }
            val answer = membershipsWithStageA(principal, stageA, fetched)
            if (answer == null) {
                // A refused credential is anonymous: the next round has no principal, so it always answers.
                state.authn = refused(checkNotNull(principal))
                continue
            }
            state.memberships = answer.valueOr {
                return state.unavailable(Cause.MembershipUnavailable(it))
            }
            state.subject = subject
            return null
        }
    }

    /** Stage A, when there is a [request] for it, together with the memberships. Null when the credential is refused. */
    private suspend fun membershipsWithStageA(
        principal: AuthenticatedPrincipal?,
        request: AttributeRequest?,
        fetched: FetchedAttributes,
    ): Answer<Memberships>? {
        if (request == null) {
            return memberships(principal)
        }
        if (principal?.membershipSource !is MembershipSource.Provider) {
            // Claims, or nothing to ask: the memberships never suspend, so there is nothing to overlap stage A with.
            val memberships = memberships(principal)
            fetching.fetchStageA(request, fetched)
            return memberships
        }
        return coroutineScope {
            val stageA = async(start = CoroutineStart.UNDISPATCHED) { fetching.fetchStageA(request, fetched) }
            val memberships = memberships(principal)
            stageA.await()
            memberships
        }
    }

    /**
     * The memberships of [principal]: none for an anonymous one, else what [memberships] resolves. Null when the
     * provider does not know the principal or it is not active.
     */
    private suspend fun memberships(principal: AuthenticatedPrincipal?): Answer<Memberships>? {
        if (principal == null) {
            return NO_MEMBERSHIPS
        }
        return try {
            memberships.resolve(principal.tenant, principal.id, principal.membershipSource)?.let { Answer.Value(it) }
        } catch (e: MembershipUnavailableException) {
            Answer.Failed(e)
        }
    }

    /**
     * The request's subject in the engine's request (`AuthzRequest`): the engine takes the tenant from the scope and
     * reads the subject's id, whether it is authenticated, and whether its principal and break-glass bindings apply.
     */
    private fun subject(principal: AuthenticatedPrincipal?): Subject = Subject(
        id = principal?.id?.value ?: CoSecPrincipal.ANONYMOUS_ID,
        authenticated = principal != null,
        attributes = principal?.attributes.orEmpty(),
        principalBindings = principal?.principalBindings ?: true,
        breakGlass = principal?.breakGlass ?: true,
    )

    /**
     * [principal]'s credential, refused: an API key as an invalid key, anything else as an invalid token; the event names
     * whose it was (review L3).
     */
    private fun refused(principal: AuthenticatedPrincipal): AuthnResult.Rejected {
        val reason = if (principal.credential?.method == Identity.Method.API_KEY) {
            AuthnResult.RejectReason.API_KEY_INVALID
        } else {
            AuthnResult.RejectReason.TOKEN_INVALID
        }
        return AuthnResult.Rejected(
            reason,
            AuthnResult.RefusedCredential(principal.tenant, principal.id, principal.credential?.id),
        )
    }

    private companion object {
        val NO_MEMBERSHIPS: Answer<Memberships> =
            Answer.Value(Memberships(Memberships.Source.CLAIMS, emptyMap(), emptyList()))
    }
}
