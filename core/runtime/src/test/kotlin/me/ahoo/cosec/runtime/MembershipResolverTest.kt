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

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.api.principal.SimplePrincipal
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.token.SimpleTokenPrincipal
import me.ahoo.cosec.spi.authn.MembershipSource
import me.ahoo.cosec.spi.membership.InMemoryMembershipProvider
import me.ahoo.cosec.spi.membership.MembershipAnswer
import me.ahoo.cosec.spi.membership.MembershipClaims
import me.ahoo.cosec.spi.membership.MembershipMarkedPrincipal
import me.ahoo.cosec.spi.membership.MembershipProvider
import me.ahoo.cosec.spi.membership.MembershipUnavailableException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * `MEM-1`, `MEM-2` (RFC 0004 §4.5.2, §10): memberships that cannot be established make the resolution fail, never
 * resolve to "none". Moved from `MembershipFailClosedTest` in `cosec-core` with the resolver (RFC 0006 PR-3).
 */
class MembershipResolverTest {
    private val tenant = TenantId("tenant")
    private val alice = SimplePrincipal("alice")
    private val provider = InMemoryMembershipProvider()
    private val providerMemberships = Memberships(
        Memberships.Source.PROVIDER,
        mapOf(SpaceId("team-a") to setOf(RoleId("editor"))),
        listOf(PolicyId("p1")),
    )

    init {
        provider.put(tenant, PrincipalId("alice"), providerMemberships)
    }

    private fun marked(marker: String = MembershipClaims.PROVIDER, principal: CoSecPrincipal = alice) =
        SimpleTokenPrincipal("jti", MembershipMarkedPrincipal(principal, marker))

    private suspend fun MembershipResolver.resolve(
        principal: CoSecPrincipal,
        tenant: TenantId = this@MembershipResolverTest.tenant,
    ): Memberships? = resolve(tenant, PrincipalId(principal.id), MembershipSource.of(principal))

    private suspend fun MembershipResolver.failure(principal: CoSecPrincipal = marked()): MembershipUnavailableException =
        try {
            resolve(principal)
            throw AssertionError("The resolution did not fail.")
        } catch (e: MembershipUnavailableException) {
            e
        }

    @Test
    fun claimsWithoutMarker() = runTest {
        val memberships = MembershipResolver(null)
            .resolve(SimplePrincipal("alice", policies = setOf("p1"), roles = setOf("admin")))!!

        memberships.source.assert().isEqualTo(Memberships.Source.CLAIMS)
        memberships.roles[SpaceId.DEFAULT]!!.toList().assert().containsExactly(RoleId("admin"))
        memberships.attachments.assert().containsExactly(PolicyId("p1"))
    }

    @Test
    fun providerAnswers() = runTest {
        MembershipResolver(provider).resolve(marked()).assert().isSameAs(providerMemberships)
    }

    /** `IDN-3`: a principal the provider does not know has no memberships to resolve, so its credential is refused. */
    @Test
    fun anUnknownPrincipalIsRefused() = runTest {
        MembershipResolver(provider).resolve(marked(principal = SimplePrincipal("bob"))).assert().isNull()
    }

    @Test
    fun anInactivePrincipalIsRefused() = runTest {
        val inactive = Memberships(Memberships.Source.PROVIDER, providerMemberships.roles, emptyList(), active = false)
        MembershipResolver(
            MembershipProvider { _, _ -> MembershipAnswer.Known(inactive) }
        ).resolve(marked()).assert().isNull()
    }

    @Test
    fun providerIsAskedForTheTokenTenant() = runTest {
        MembershipResolver(provider).resolve(marked(), TenantId("other")).assert().isNull()
    }

    private val wide = Memberships(
        Memberships.Source.PROVIDER,
        mapOf(
            SpaceId.DEFAULT to setOf(RoleId("admin"), RoleId("viewer")),
            SpaceId("team-a") to setOf(RoleId("editor")),
        ),
        listOf(PolicyId("p1")),
    )

    private suspend fun capped(source: MembershipSource): Memberships =
        MembershipResolver(
            MembershipProvider { _, _ -> MembershipAnswer.Known(wide) }
        ).resolve(tenant, PrincipalId("svc"), source)!!

    /** `IDN-2`: a cap keeps only the roles it names, in the same space, and never an attached policy. */
    @Test
    fun aCapNarrowsByExactSpaceAndRole() = runTest {
        val memberships =
            capped(
                MembershipSource.Provider.capped(listOf("viewer", "editor@team-a", "editor", "admin@team-a", "ghost"))
            )
        memberships.roles.assert().isEqualTo(
            mapOf(SpaceId.DEFAULT to setOf(RoleId("viewer")), SpaceId("team-a") to setOf(RoleId("editor"))),
        )
        memberships.attachments.assert().isEmpty()
        memberships.source.assert().isEqualTo(Memberships.Source.PROVIDER)
    }

    @Test
    fun anEmptyCapLeavesNothing() = runTest {
        val memberships = capped(MembershipSource.Provider.capped(emptyList()))
        memberships.roles.assert().isEmpty()
        memberships.attachments.assert().isEmpty()
    }

    /** `IDN-1`: without a cap, the key is its principal: never more. */
    @Test
    fun noCapIsThePrincipalsMemberships() = runTest {
        capped(MembershipSource.Provider.UNCAPPED).assert().isSameAs(wide)
    }

    @Test
    fun noProvider() = runTest {
        MembershipResolver(null).failure().message.assert().contains("none is configured")
    }

    @Test
    fun unknownMarker() = runTest {
        MembershipResolver(provider).failure(marked(marker = "claims"))
            .message.assert().contains("unknown membership marker [claims]")
    }

    @Test
    fun markerWithClaimedRolesIsAmbiguous() = runTest {
        MembershipResolver(provider).failure(marked(principal = SimplePrincipal("alice", roles = setOf("admin"))))
            .message.assert().contains("also claims roles or policies")
    }

    @Test
    fun markerWithClaimedPoliciesIsAmbiguous() = runTest {
        MembershipResolver(provider).failure(marked(principal = SimplePrincipal("alice", policies = setOf("p"))))
            .message.assert().contains("also claims roles or policies")
    }

    @Test
    fun providerThrows() = runTest {
        val failure = MembershipResolver(MembershipProvider { _, _ -> throw IllegalStateException("down") }).failure()

        failure.cause.assert().isInstanceOf(IllegalStateException::class.java)
        failure.message.assert().contains("failed")
    }

    /** A CancellationException the provider throws while nobody cancelled the request is a failure, not a cancellation. */
    @Test
    fun providerThrowsAStrayCancellation() = runTest {
        MembershipResolver(MembershipProvider { _, _ -> throw CancellationException("not ours") }).failure()
            .cause.assert().isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun providerAnswersClaimsSourcedMemberships() = runTest {
        val claims = MembershipClaims.parse(listOf("admin"), emptyList())
        MembershipResolver(
            MembershipProvider { _, _ -> MembershipAnswer.Known(claims) }
        ).failure().message.assert().contains("[CLAIMS]")
    }

    /** A provider written in Java can answer null: that is no answer, so it fails, as before RFC 0009. */
    @Test
    fun providerReturnsNull() = runTest {
        MembershipResolver(MembershipProvider { _, _ -> uncheckedNull() }).failure()
            .message.assert().contains("answered nothing")
    }

    @Test
    fun providerTimesOut() = runTest {
        MembershipResolver(MembershipProvider { _, _ -> awaitCancellation() }, 50.milliseconds).failure()
            .message.assert().contains("did not answer within 50ms")
    }

    @Test
    fun timeoutMustBePositive() {
        assertThrows<IllegalArgumentException> { MembershipResolver(provider, Duration.ZERO) }
    }

    private companion object {
        @Suppress("UNCHECKED_CAST")
        fun <T> uncheckedNull(): T = null as T
    }
}
