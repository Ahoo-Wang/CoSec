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

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.ahoo.cosec.spi.membership.MembershipProvider
import me.ahoo.cosec.spi.membership.MembershipUnavailableException
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.authn.MembershipSource
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The memberships of a principal (RFC 0004 §4.5): the token's claims, or the [provider]'s answer when the token
 * carries the provider marker. One principal has one source; claims and provider are never combined.
 *
 * Fails closed: [resolve] throws [MembershipUnavailableException], never answers empty memberships, when the source is
 * [MembershipSource.Unusable] (`MEM-1`), or the token asks for a provider and there is none, or the provider throws,
 * answers memberships whose source is not [Memberships.Source.PROVIDER], or does not answer within [timeout] (`MEM-2`).
 * The pipeline turns that into `Indeterminate(MembershipUnavailable)`, answered 503.
 *
 * The provider is called through [resumeOffIo], so an answer that completes on a client library's thread does not
 * continue the request there (RFC 0006 §7.2). An external cancellation propagates.
 */
class MembershipResolver(
    private val provider: MembershipProvider?,
    /** `cosec.authorization.membership.timeout` (RFC 0004 §4.5.2, RFC 0006 Q25). */
    private val timeout: Duration = DEFAULT_TIMEOUT,
) {
    init {
        require(timeout.isPositive()) { "The membership timeout must be positive, not $timeout." }
    }

    /** The memberships of [principal] in [tenant], whose token states [source]. */
    suspend fun resolve(tenant: TenantId, principal: PrincipalId, source: MembershipSource): Memberships =
        when (source) {
            is MembershipSource.Claims -> source.memberships
            is MembershipSource.Unusable -> throw MembershipUnavailableException(source.reason)
            MembershipSource.Provider -> provided(tenant, principal)
        }

    private suspend fun provided(tenant: TenantId, principal: PrincipalId): Memberships {
        val provider = provider ?: throw MembershipUnavailableException(
            "The token of principal [${principal.value}] asks for a membership provider, and none is configured."
        )
        val answer = ask(provider, tenant, principal) ?: throw MembershipUnavailableException(
            "The membership provider did not answer within $timeout for principal [${principal.value}]."
        )
        return checked(answer, principal)
    }

    /**
     * The provider's answer, or null when it did not answer in time. A provider written in Java may answer null;
     * [NOTHING] stands for that, as [withLazyTimeoutOrNull]'s own null means the timeout. An external cancellation
     * propagates; any other exception, a CancellationException the provider throws while this request is active
     * included, is a failure.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun ask(provider: MembershipProvider, tenant: TenantId, principal: PrincipalId): Memberships? =
        try {
            withLazyTimeoutOrNull(timeout) {
                resumeOffIo { provider.memberships(tenant, principal) as Memberships? ?: NOTHING }
            }
        } catch (e: CancellationException) {
            // Rethrows when the request itself was cancelled.
            currentCoroutineContext().ensureActive()
            throw failure(principal, e)
        } catch (e: Exception) {
            throw failure(principal, e)
        }

    private fun checked(answer: Memberships, principal: PrincipalId): Memberships {
        if (answer === NOTHING) {
            throw MembershipUnavailableException(
                "The membership provider answered nothing for principal [${principal.value}]."
            )
        }
        if (answer.source != Memberships.Source.PROVIDER) {
            throw MembershipUnavailableException(
                "The membership provider answered memberships from [${answer.source}] for principal [${principal.value}]."
            )
        }
        return answer
    }

    private fun failure(principal: PrincipalId, error: Throwable) =
        MembershipUnavailableException("The membership provider failed for principal [${principal.value}].", error)

    companion object {
        val DEFAULT_TIMEOUT: Duration = 500.milliseconds

        private val NOTHING = Memberships(Memberships.Source.PROVIDER, emptyMap(), emptyList())
    }
}
