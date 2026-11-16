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

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.api.ControlHttp
import me.ahoo.cosec.control.api.ControlHttpRequest
import me.ahoo.cosec.control.api.DefaultControlPlane
import me.ahoo.cosec.control.change.ChangeAuthority
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeOptions
import me.ahoo.cosec.control.change.Changes
import me.ahoo.cosec.control.change.allow
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.control.store.StoreFixtures
import me.ahoo.cosec.document.PolicyDefinition
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.engine.BuiltinPolicies
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * `SCP-13` (#1403): a tenant id is one path segment of the resources the control plane authorizes on. A platform
 * operator given tenant `acme` the way one tenant is delegated, `cosec:read` on `doc/tenant/acme` and on every
 * resource under it (`doc/tenant/acme/` and `**`), reads `acme`'s document and no other tenant's. Were `acme/west` a
 * tenant id that the resource took as it is, its document `doc/tenant/acme/west` would be under it: the hard rule
 * (`TenantIsolation`) lets a platform principal act on any tenant, so only the resource keeps them apart.
 */
class OneSegmentTenantIdTest {
    private val clock = MutableClock()
    private val store = InMemoryControlStore(clock)
    private val revisions = Revisions(store, clock)
    private val engine = EngineAuthority(revisions)
    private val changes = Changes(store, revisions, { ChangeId("c") }, engine.authorizer, clock, ChangeOptions())
    private val http = ControlHttp(DefaultControlPlane(changes, revisions, ChangeAuthority(engine.authorizer)))
    private val plato = engine.operator(TenantId.PLATFORM, "plato")
    private val tia = engine.security(TenantId.PLATFORM, "tia")

    private suspend fun bootstrap(key: SnapshotKey, policies: List<PolicyDefinition>, bindings: List<Binding>) {
        revisions.publish(
            key,
            revisions.head(key),
            StoreFixtures.candidate(key, policies, bindings),
            Publication("bootstrap", ProvenanceKind.BOOTSTRAP, plato.actor),
        )
    }

    private fun control(id: String, principal: String, policy: PolicyId) = Binding(
        BindingId(id),
        SubjectRef.Principal(TenantId.PLATFORM, PrincipalId(principal)),
        BindingScope(Match.Exactly(PipelineControlAuthorizer.CONTROL_APP), Match.Exactly(SpaceId.DEFAULT)),
        policy,
    )

    /** The document of [tenant] when a key can name it: no key names `acme/west` unless `/` is let back in. */
    private fun keyOf(tenant: String): SnapshotKey? = try {
        SnapshotKey.Tenant(tenant)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun read(document: String): Int = runBlocking {
        http.handle(ControlHttpRequest("GET", "/documents/$document", emptyMap(), null), tia).status
    }

    @Test
    fun `an operator delegated one tenant reads no document of a tenant whose id extends it`() = runBlocking<Unit> {
        bootstrap(
            SnapshotKey.Platform,
            listOf(
                allow("acme-operator", action = "cosec:read", resource = "doc/tenant/acme"),
                allow("acme-entries", action = "cosec:read", resource = "doc/tenant/acme/**"),
            ),
            listOf(
                control("plato", "plato", BuiltinPolicies.PLATFORM_ADMIN),
                control("tia-acme", "tia", PolicyId("acme-operator")),
                control("tia-acme-entries", "tia", PolicyId("acme-entries")),
            ),
        )
        bootstrap(SnapshotKey.Tenant("acme"), emptyList(), emptyList())
        keyOf("acme/west")?.let { bootstrap(it, emptyList(), emptyList()) }

        read("tenant:acme").assert().isEqualTo(200)
        read("tenant:globex").assert().describedAs("another tenant").isEqualTo(403)
        // Not one resource segment, so it names no document at all; never acme/west's, read through acme's grant.
        read("tenant:acme%2Fwest").assert().isEqualTo(400)
    }
}
