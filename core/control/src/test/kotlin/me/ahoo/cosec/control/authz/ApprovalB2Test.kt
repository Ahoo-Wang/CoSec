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

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ChangeAuthority
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeOptions
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.change.ChangeRequest
import me.ahoo.cosec.control.change.ChangeState
import me.ahoo.cosec.control.change.Changes
import me.ahoo.cosec.control.change.IMMEDIATE_WINDOW
import me.ahoo.cosec.control.change.Operation
import me.ahoo.cosec.control.change.allow
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.engine.flat.BuiltinPolicies
import me.ahoo.cosec.engine.flat.ResourceSource
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.document.PolicyDefinition
import me.ahoo.cosec.runtime.document.ProvenanceKind
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.MembershipSource
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.membership.MembershipAnswer
import me.ahoo.cosec.spi.membership.MembershipProvider
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * RC review batch B2 through the real pipeline and engine: the trust root is the platform's (RC1-10), so is a tenant's
 * boundary; an approval is a person's, checked again for revocation (RC1-11) and on what the membership provider
 * answers now (RC1-11, RC1-13).
 */
class ApprovalB2Test {
    private val clock = MutableClock()
    private val store = InMemoryControlStore(clock)
    private val revisions = Revisions(store, clock)

    /** carol and dave's roles, as the membership provider answers them now. */
    private val provided = ConcurrentHashMap<String, Set<RoleId>>()
    private val provider = MembershipProvider { tenant, principal ->
        provided[principal.value]?.let { roles ->
            MembershipAnswer.Known(Memberships(Memberships.Source.PROVIDER, mapOf(SpaceId.DEFAULT to roles), emptyList()))
        } ?: MembershipAnswer.Unknown.also { check(tenant.value.isNotEmpty()) }
    }
    private val engine = EngineAuthority(revisions, provider)
    private val ids = AtomicInteger()

    /** Revoked token ids, and the instant before which a principal's tokens are revoked. */
    private val revokedIds = ConcurrentHashMap.newKeySet<String>()
    private val revokedBefore = ConcurrentHashMap<String, Instant>()
    private val revocations = object : RevocationReader {
        override suspend fun isRevoked(tokenId: String): Boolean = tokenId in revokedIds

        override suspend fun subjectRevokedBefore(tenant: TenantId, principal: PrincipalId): Instant? =
            revokedBefore["${tenant.value}/${principal.value}"]
    }

    private fun changes(options: ChangeOptions = ChangeOptions(shadow = IMMEDIATE_WINDOW)) = Changes(
        store,
        revisions,
        { ChangeId("c${ids.incrementAndGet()}") },
        engine.authorizer,
        clock,
        options,
        authority = ChangeAuthority(
            engine.authorizer,
            options,
            revocations = revocations,
            revocationSkew = Duration.ZERO
        ),
    )

    private val changes = changes()

    private val plato = engine.operator(TenantId.PLATFORM, "plato")
    private val pia = engine.operator(TenantId.PLATFORM, "pia")
    private val alice = engine.operator(ACME, "alice", roles = listOf("admin", "ops@team-a"))

    /** acme's own approver: the platform delegated cosec:approve on acme's document to it. */
    private val carol = engine.operator(ACME, "carol")

    init {
        runBlocking {
            bootstrap(
                SnapshotKey.Platform,
                listOf(
                    PolicyDefinition(PolicyId("fence"), false, null, allow("x").statements, boundary = true),
                    PolicyDefinition(
                        PolicyId("acme-approver"),
                        false,
                        null,
                        listOf(
                            StatementSource(
                                "",
                                Effect.ALLOW,
                                listOf(
                                    "cosec:approve",
                                    "cosec:read",
                                    "cosec:write",
                                    "cosec:grant",
                                    "cosec:publish",
                                    "cosec:boundary"
                                ),
                                listOf(
                                    ResourceSource("doc/tenant/acme/**"),
                                    ResourceSource("doc/tenant/acme"),
                                    ResourceSource("scope/acme/**")
                                )
                            ),
                        ),
                    ),
                ),
                listOf(
                    control(
                        "plato",
                        SubjectRef.Principal(TenantId.PLATFORM, PrincipalId("plato")),
                        BuiltinPolicies.PLATFORM_ADMIN
                    ),
                    control(
                        "pia",
                        SubjectRef.Principal(TenantId.PLATFORM, PrincipalId("pia")),
                        BuiltinPolicies.PLATFORM_ADMIN
                    ),
                    control("carol", SubjectRef.Principal(ACME, PrincipalId("carol")), PolicyId("acme-approver")),
                ),
            )
            bootstrap(
                TENANT,
                listOf(
                    allow("orders", action = "order:*", resource = "order/**"),
                    // A suspended approver approves nothing.
                    PolicyDefinition(
                        PolicyId("no-approve"),
                        false,
                        null,
                        listOf(StatementSource("", Effect.DENY, listOf("cosec:approve"), listOf(ResourceSource("doc/**")))),
                    ),
                ),
                listOf(
                    control("admins", SubjectRef.Role(ACME, RoleId("admin")), BuiltinPolicies.TENANT_ADMIN),
                    control("suspended", SubjectRef.Role(ACME, RoleId("suspended")), PolicyId("no-approve")),
                    control("approvers", SubjectRef.Role(ACME, RoleId("approver")), PolicyId("acme-approver")),
                    Binding(
                        BindingId("ops"),
                        SubjectRef.Role(ACME, RoleId("ops")),
                        BindingScope(Match.Exactly(ORDER), Match.Any),
                        PolicyId("orders")
                    ),
                ),
            )
        }
    }

    private suspend fun bootstrap(key: SnapshotKey, policies: List<PolicyDefinition>, bindings: List<Binding>) {
        revisions.publish(
            key,
            revisions.head(key),
            StoreFixtures.candidate(key, policies, bindings),
            Publication("bootstrap", ProvenanceKind.BOOTSTRAP, plato.actor)
        )
    }

    private fun control(id: String, subject: SubjectRef, policy: PolicyId) = Binding(
        BindingId(id),
        subject,
        BindingScope(Match.Exactly(PipelineControlAuthorizer.CONTROL_APP), Match.Exactly(SpaceId.DEFAULT)),
        policy
    )

    /** Grants `order:read` on one order to acme's viewers in every space of the order app: more than alice holds. */
    private fun grant(policy: String) = listOf(
        Operation.PutPolicy(allow(policy, action = "order:read", resource = "order/{id}")),
        Operation.PutBinding(
            Binding(
                BindingId(policy),
                SubjectRef.Role(ACME, RoleId("viewer")),
                BindingScope(Match.Exactly(ORDER), Match.Any),
                PolicyId(policy)
            )
        ),
    )

    private suspend fun head(key: SnapshotKey = TENANT): Long = revisions.head(key)?.revision ?: 0

    private suspend fun refused(block: suspend () -> Unit): Int = try {
        block()
        error("not refused")
    } catch (e: ChangeRejectedException) {
        e.status
    }

    /** alice's change, past its window, waiting for an approval. */
    private suspend fun awaiting(policy: String): ChangeRequest {
        val change = changes.propose(alice, TENANT, head(), grant(policy), policy)
        changes.stage(alice, change.id)
        return changes.shadowed(change.id).also { it.state.assert().isEqualTo(ChangeState.AWAITING_APPROVAL) }
    }

    /** An operator authenticated by a token of id [jti] issued at [issuedAt], its memberships claimed or provided. */
    private fun withToken(tenant: TenantId, id: String, jti: String, issuedAt: Instant?, provided: Boolean = false) =
        engine.operator(
            AuthenticatedPrincipal(
                PrincipalId(id),
                tenant,
                if (provided) {
                    MembershipSource.Provider.UNCAPPED
                } else {
                    MembershipSource.Claims(Memberships(Memberships.Source.CLAIMS, emptyMap(), emptyList()))
                },
                emptyMap(),
                Identity.Credential(Identity.Method("oidc"), jti, null),
                breakGlass = false,
                issuedAt = issuedAt,
                apiKeyCredential = false,
            )
        )

    /** RC1-10: holding cosec:approve on its own tenant's document does not make carol the trust root. */
    @Test
    fun `a tenant approver's own change is analyzed, and it approves others' - RC1-10`() = runBlocking<Unit> {
        val own = changes.propose(carol, TENANT, head(), grant("carols"), "mine")
        own.escalations.map { it.reason }.assert().containsExactly("NOT_COVERED")
        changes.abandon(carol, own.id)
        // A platform administrator's is not analyzed: the trust root publishes alone.
        changes.propose(plato, TENANT, head(), grant("platos"), "the platform's").escalations.assert().isEmpty()
        val others = awaiting("viewers")
        changes.approve(carol, others.id, others.candidate!!).state.assert().isEqualTo(ChangeState.READY)
        changes.publish(alice, others.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
    }

    /** RC1-10 (RFC 0007 §9.1): a tenant's boundary is the platform's ceiling on it. */
    @Test
    fun `only a platform principal sets a tenant's boundary or approves it`() = runBlocking<Unit> {
        val set = listOf(Operation.SetBoundary(PolicyId("fence")))
        refused { changes.propose(alice, TENANT, head(), set, "mine") }.assert().isEqualTo(403)
        refused { changes.propose(carol, TENANT, head(), set, "mine") }.assert().isEqualTo(403)
        val fourEyes = changes(ChangeOptions(shadow = IMMEDIATE_WINDOW, fourEyes = true))
        val change = fourEyes.propose(plato, TENANT, head(), set, "fence acme")
        change.escalations.map { it.reason }.assert().containsExactly("NOT_ANALYZED")
        fourEyes.stage(plato, change.id)
        val waiting = fourEyes.shadowed(change.id)
        refused { fourEyes.approve(carol, change.id, waiting.candidate!!) }.assert().isEqualTo(403)
        fourEyes.approve(pia, change.id, waiting.candidate!!).state.assert().isEqualTo(ChangeState.READY)
        fourEyes.publish(plato, change.id).state.assert().isEqualTo(ChangeState.PUBLISHED)
        revisions.head(TENANT)!!.document.boundary.assert().isEqualTo(PolicyId("fence"))
    }

    /** RC1-11: an approval is a person's two-person rule; an API key is a workload's credential. */
    @Test
    fun `an API key never approves`() = runBlocking<Unit> {
        val change = awaiting("viewers")
        val key = engine.operator(
            AuthenticatedPrincipal(
                PrincipalId("pia"),
                TenantId.PLATFORM,
                Memberships(Memberships.Source.CLAIMS, emptyMap(), emptyList()),
                credential = Identity.Credential(Identity.Method.API_KEY, "key-1", null),
            )
        )
        refused { changes.approve(key, change.id, change.candidate!!) }.assert().isEqualTo(403)
        changes.approve(pia, change.id, change.candidate!!).state.assert().isEqualTo(ChangeState.READY)
    }

    /**
     * Review M2: an API key is never the trust root, though its principal holds `cosec:platform-admin`: so CoSec's API
     * key authenticator says, by provenance, whatever the method is called, and so does a forwarded identity that
     * carries the reserved method name (fail closed). Its platform change waits for a person, and it approves nothing.
     */
    @Test
    fun `an API key is never the trust root - review M2`() = runBlocking<Unit> {
        val platform = listOf(Operation.PutPolicy(allow("keys", action = "order:read", resource = "order/**")))
        changes.propose(pia, SnapshotKey.Platform, head(SnapshotKey.Platform), platform, "a person")
            .escalations.assert().isEmpty()
        val byProvenance = engine.operator(
            AuthenticatedPrincipal(
                PrincipalId("pia"),
                TenantId.PLATFORM,
                MembershipSource.Claims(Memberships(Memberships.Source.CLAIMS, emptyMap(), emptyList())),
                emptyMap(),
                Identity.Credential(Identity.Method("service-key"), "key-2", null),
                breakGlass = false,
                issuedAt = null,
                apiKeyCredential = true,
            )
        )
        val byName = engine.operator(
            AuthenticatedPrincipal(
                PrincipalId("pia"),
                TenantId.PLATFORM,
                Memberships(Memberships.Source.CLAIMS, emptyMap(), emptyList()),
                credential = Identity.Credential(Identity.Method.API_KEY, "key-1", null),
            )
        )
        for (key in listOf(byProvenance, byName)) {
            val change = changes.propose(key, SnapshotKey.Platform, head(SnapshotKey.Platform), platform, "a key")
            change.escalations.map { it.reason }.assert().containsExactly("NOT_ANALYZED")
            changes.stage(key, change.id)
            val waiting = changes.shadowed(change.id)
            waiting.state.assert().isEqualTo(ChangeState.AWAITING_APPROVAL)
            refused { changes.publish(key, change.id) }.assert().isEqualTo(409)
            refused { changes.approve(byProvenance, change.id, waiting.candidate!!) }.assert().isEqualTo(403)
            changes.abandon(key, change.id)
        }
    }

    /** RC1-11: the approval records its token's id and `iat`; a revocation since takes it back. */
    @Test
    fun `an approval by a token revoked since does not count`() = runBlocking<Unit> {
        val issued = clock.now
        val token = withToken(TenantId.PLATFORM, "pia", "jti-pia", issued)
        suspend fun publishedAfter(revoke: () -> Unit): ChangeState {
            val change = awaiting("viewers-${ids.get()}")
            changes.approve(token, change.id, change.candidate!!).approval!!.credentialId.assert().isEqualTo("jti-pia")
            revoke()
            return changes.publish(alice, change.id).state.also {
                revokedIds.clear()
                revokedBefore.clear()
                if (it != ChangeState.PUBLISHED) changes.abandon(alice, change.id)
            }
        }
        publishedAfter { revokedIds += "jti-pia" }.assert().isEqualTo(ChangeState.AWAITING_APPROVAL)
        publishedAfter { revokedBefore["(platform)/pia"] = issued.plusSeconds(1) }
            .assert().isEqualTo(ChangeState.AWAITING_APPROVAL)
        // An approval whose credential said nothing is taken back by any revocation of its approver's tokens.
        val change = awaiting("viewers-untokened")
        changes.approve(pia, change.id, change.candidate!!).approval!!.credentialId.assert().isNull()
        revokedBefore["(platform)/pia"] = issued.minusSeconds(60)
        changes.publish(alice, change.id).state.assert().isEqualTo(ChangeState.AWAITING_APPROVAL)
        revokedBefore.clear()
        changes.abandon(alice, change.id)
        // Revoking the tokens issued before an older instant leaves this one standing.
        publishedAfter { revokedBefore["(platform)/pia"] = issued.minusSeconds(60) }
            .assert().isEqualTo(ChangeState.PUBLISHED)
    }

    /**
     * RC1-11, RC1-13: an approver whose memberships come from the provider counts with what it answers now: dave, one
     * of acme's approvers by role, approves, and loses the role before the change publishes.
     */
    @Test
    fun `an approver from the provider counts with the roles it holds now`() = runBlocking<Unit> {
        provided["dave"] = setOf(RoleId("approver"))
        val dave = withToken(ACME, "dave", "jti-dave", clock.now, provided = true)
        val kept = awaiting("viewers")
        changes.approve(dave, kept.id, kept.candidate!!)
        changes.publish(alice, kept.id).state.assert().isEqualTo(ChangeState.PUBLISHED)

        val lost = awaiting("viewers-again")
        changes.approve(dave, lost.id, lost.candidate!!)
        provided["dave"] = emptySet()
        changes.publish(alice, lost.id).state.assert().isEqualTo(ChangeState.AWAITING_APPROVAL)

        // One the provider no longer knows does not count either.
        provided["dave"] = setOf(RoleId("approver"))
        val gone = changes.get(lost.id)
        changes.approve(dave, lost.id, gone.candidate!!)
        provided.remove("dave")
        changes.publish(alice, lost.id).state.assert().isEqualTo(ChangeState.AWAITING_APPROVAL)
        changes.abandon(alice, lost.id)

        // A role gained since counts for its DENYs: suspended, dave approves nothing (deny-first).
        provided["dave"] = setOf(RoleId("approver"))
        val suspended = awaiting("viewers-suspended")
        changes.approve(dave, suspended.id, suspended.candidate!!)
        provided["dave"] = setOf(RoleId("approver"), RoleId("suspended"))
        changes.publish(alice, suspended.id).state.assert().isEqualTo(ChangeState.AWAITING_APPROVAL)
        changes.abandon(alice, suspended.id)

    }

    private companion object {
        val ACME = TenantId("acme")
        val TENANT: SnapshotKey = SnapshotKey.Tenant("acme")
        val ORDER = AppId("order")
    }
}
