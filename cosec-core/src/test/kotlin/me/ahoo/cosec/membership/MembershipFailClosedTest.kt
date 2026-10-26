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

package me.ahoo.cosec.membership

import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.token.SimpleTokenPrincipal
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * RFC 0004 §4.5.2 and §10: memberships that cannot be established make the resolution fail, never resolve to "none".
 */
class MembershipFailClosedTest {
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

    private fun CompletableFuture<Memberships>.failure(): MembershipUnavailableException {
        val error = assertThrows<ExecutionException> { get(5, TimeUnit.SECONDS) }
        return error.cause as MembershipUnavailableException
    }

    @Test
    fun claimsWithoutMarker() {
        val memberships = MembershipResolver(null)
            .resolve(tenant, SimplePrincipal("alice", policies = setOf("p1"), roles = setOf("admin")))
            .getNow(null)

        memberships.source.assert().isEqualTo(Memberships.Source.CLAIMS)
        memberships.roles[SpaceId.DEFAULT]!!.toList().assert().containsExactly(RoleId("admin"))
        memberships.attachments.assert().containsExactly(PolicyId("p1"))
    }

    @Test
    fun providerAnswers() {
        MembershipResolver(provider).resolve(tenant, marked()).get(5, TimeUnit.SECONDS)
            .assert().isSameAs(providerMemberships)
    }

    @Test
    fun providerAnswersNoneForAnUnknownPrincipal() {
        val memberships = MembershipResolver(provider).resolve(tenant, marked(principal = SimplePrincipal("bob")))
            .get(5, TimeUnit.SECONDS)

        memberships.source.assert().isEqualTo(Memberships.Source.PROVIDER)
        memberships.roles.assert().isEmpty()
    }

    @Test
    fun providerIsAskedForTheTokenTenant() {
        MembershipResolver(provider).resolve(TenantId("other"), marked()).get(5, TimeUnit.SECONDS)
            .roles.assert().isEmpty()
    }

    @Test
    fun noProvider() {
        MembershipResolver(null).resolve(tenant, marked()).failure()
            .message.assert().contains("none is configured")
    }

    @Test
    fun unknownMarker() {
        MembershipResolver(provider).resolve(tenant, marked(marker = "claims")).failure()
            .message.assert().contains("unknown membership marker [claims]")
    }

    @Test
    fun markerWithClaimedRolesIsAmbiguous() {
        val principal = marked(principal = SimplePrincipal("alice", roles = setOf("admin")))
        MembershipResolver(provider).resolve(tenant, principal).failure()
            .message.assert().contains("also claims roles or policies")
    }

    @Test
    fun markerWithClaimedPoliciesIsAmbiguous() {
        val principal = marked(principal = SimplePrincipal("alice", policies = setOf("p")))
        MembershipResolver(provider).resolve(tenant, principal).failure()
            .message.assert().contains("also claims roles or policies")
    }

    @Test
    fun providerThrows() {
        val failure = MembershipResolver(MembershipProvider { _, _ -> throw IllegalStateException("down") })
            .resolve(tenant, marked()).failure()

        failure.cause.assert().isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun providerFails() {
        val failing = MembershipProvider { _, _ -> CompletableFuture.failedFuture(IllegalStateException("down")) }
        val failure = MembershipResolver(failing).resolve(tenant, marked()).failure()

        failure.cause.assert().isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun providerFailsWithACompletionException() {
        val cause = IllegalStateException("down")
        val failure = MembershipResolver(
            MembershipProvider { _, _ ->
                CompletableFuture.supplyAsync<Memberships> { throw cause }
            }
        ).resolve(tenant, marked()).failure()

        failure.cause.assert().isSameAs(cause)
    }

    @Test
    fun providerFailsWithACompletionExceptionWithoutCause() {
        val error = CompletionException(null as Throwable?)
        val failure = MembershipResolver(MembershipProvider { _, _ -> CompletableFuture.failedFuture(error) })
            .resolve(tenant, marked()).failure()

        failure.cause.assert().isSameAs(error)
    }

    @Test
    fun providerCompletesWithNull() {
        MembershipResolver(MembershipProvider { _, _ -> CompletableFuture.completedFuture(null) })
            .resolve(tenant, marked()).failure()
            .message.assert().contains("answered nothing")
    }

    @Test
    fun providerAnswersClaimsSourcedMemberships() {
        val claims = MembershipClaims.parse(listOf("admin"), emptyList())
        MembershipResolver(MembershipProvider { _, _ -> CompletableFuture.completedFuture(claims) })
            .resolve(tenant, marked()).failure()
            .message.assert().contains("[CLAIMS]")
    }

    @Test
    fun providerReturnsNull() {
        // what a provider written in Java can do
        val javaLike = MembershipProvider { _, _ -> uncheckedNull() }
        MembershipResolver(javaLike).resolve(tenant, marked()).failure()
            .message.assert().contains("answered nothing")
    }

    @Test
    fun providerTimesOutWithoutCompletingItsOwnFuture() {
        val pending = CompletableFuture<Memberships>()
        val failure = MembershipResolver(MembershipProvider { _, _ -> pending }, Duration.ofMillis(50))
            .resolve(tenant, marked()).failure()

        failure.cause.assert().isInstanceOf(TimeoutException::class.java)
        failure.message.assert().contains("did not answer within")
        // a future the provider may share with other callers stays its own
        pending.isDone.assert().isFalse()
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
