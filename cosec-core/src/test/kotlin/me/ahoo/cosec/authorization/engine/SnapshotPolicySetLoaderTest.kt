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

package me.ahoo.cosec.authorization.engine

import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import me.ahoo.cosec.api.context.request.AppIdCapable
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.context.request.SpaceIdCapable
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.authorization.MissingPolicyException
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.engine.AbsentReason
import me.ahoo.cosec.engine.LayerInput
import me.ahoo.cosec.engine.PolicySet
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.snapshot.DocumentState
import me.ahoo.cosec.snapshot.Snapshot
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class SnapshotPolicySetLoaderTest {
    private val compiler = PolicySnapshotCompiler()
    private val unknownCondition = """{"noSuchMatcher":{}}"""

    private val data = LegacyData(
        policies = linkedMapOf(
            "broken" to policyJson("broken", condition = unknownCondition),
            "g" to policyJson("g", "global"),
            "p1" to policyJson("p1"),
            "p2" to policyJson("p2"),
        ),
        globalIndex = listOf("g"),
        appPermissions = mapOf(
            "app" to appPermissionJson("app", permissionJson("perm")),
            "broken" to appPermissionJson("broken", permissionJson("perm"), unknownCondition),
        ),
        grants = mapOf("app" to mapOf("r1" to """["perm"]""", "r2" to """["ghost"]""", "r3" to "{}", "r1@s1" to "[]")),
    )

    private fun reads(
        policies: Set<String> = emptySet(),
        roles: Set<String> = emptySet(),
        appId: String = "app",
        spaceId: String = "",
    ): SnapshotReads {
        val request = EvaluateRequest(
            headers = mapOf(AppIdCapable.APP_ID_KEY to appId, SpaceIdCapable.SPACE_ID_KEY to spaceId)
                .filterValues { it.isNotEmpty() },
        )
        val context = SimpleSecurityContext(SimplePrincipal("user", policies, roles))
        return SnapshotReads.of(LegacyFacts.authzRequest(request, context).host as LegacyFacts)
    }

    private fun load(reads: SnapshotReads, data: LegacyData = this.data): PolicySet =
        SnapshotPolicySetLoader.load(reads, compiledSnapshot(data, reads.appId, compiler))

    private fun failedSnapshot(appId: String?): PolicySnapshot {
        val error = IllegalStateException("down")
        val app = appId?.let { snapshotEntry(SnapshotKey.App(it), DocumentState.Failed(error)) }
        return Snapshot(snapshotEntry(SnapshotKey.Policies, DocumentState.Failed(error)), app)
    }

    @Test
    fun aRequestWithoutPoliciesOrRolesNeedsNoAppDocument() {
        val reads = reads()
        reads.appId.assert().isNull()
        val snapshot = compiledSnapshot(data, null, compiler)
        val layers = SnapshotPolicySetLoader.load(reads, snapshot).layers
        layers[0].assert().isSameAs((snapshot.policies as DocumentState.Present).compiled.globalLayer)
        (layers[1] as LayerInput.Absent).reason.assert().isEqualTo(AbsentReason.NO_POLICIES)
        (layers[2] as LayerInput.Absent).reason.assert().isEqualTo(AbsentReason.NO_ROLES)
    }

    @Test
    fun principalPoliciesAreInThePrincipalsOrder() {
        val layer = load(reads(policies = linkedSetOf("p2", "g", "p1"))).layers[1] as LayerInput.Loaded
        layer.groups.map { it.id }.assert().containsExactly("p2", "g", "p1")
        layer.missing.assert().isNull()
    }

    @Test
    fun aMissingPolicyFailsThePrincipalLayer() {
        val layer = load(reads(policies = linkedSetOf("x", "p1", "y"))).layers[1] as LayerInput.Loaded
        layer.groups.assert().isEmpty()
        layer.missing!!.policyIds.assert().containsExactly("x", "y")
        layer.missing!!.error.assert().isInstanceOf(MissingPolicyException::class.java)
    }

    @Test
    fun aBrokenPolicyFailsThePrincipalLayerEvenWithAMissingOne() {
        val layer = load(reads(policies = linkedSetOf("x", "broken"))).layers[1]
        layer.assert().isInstanceOf(LayerInput.Unavailable::class.java)
    }

    @Test
    fun withoutAPoliciesDocumentEveryPolicyIsMissing() {
        val empty = LegacyData()
        val layers = load(reads(policies = setOf("p1")), empty).layers
        layers[0].assert().isSameAs(PolicySnapshotCompiler.NO_GLOBAL_POLICIES)
        (layers[1] as LayerInput.Loaded).missing!!.policyIds.assert().containsExactly("p1")
    }

    @Test
    fun aFailedDocumentFailsTheLayersItFeeds() {
        val reads = reads(policies = setOf("p1"), roles = setOf("r1"))
        val layers = SnapshotPolicySetLoader.load(reads, failedSnapshot("app")).layers
        layers.forEach { it.assert().isInstanceOf(LayerInput.Unavailable::class.java) }
        // Layers that read nothing stay absent.
        val absent = SnapshotPolicySetLoader.load(reads(), failedSnapshot(null)).layers
        absent[1].assert().isInstanceOf(LayerInput.Absent::class.java)
        absent[2].assert().isInstanceOf(LayerInput.Absent::class.java)
    }

    @Test
    fun roleGrantsFollowThePrincipalsRoleOrderAndSkipRolesWithoutGrants() {
        val reads = reads(roles = linkedSetOf("r2", "none", "r1"))
        reads.appId.assert().isEqualTo("app")
        val snapshot = compiledSnapshot(data, "app", compiler)
        val group = (SnapshotPolicySetLoader.load(reads, snapshot).layers[2] as LayerInput.Loaded).groups.single()
            as AppPermissionGroup
        val roles = ArrayList<String?>()
        group.forEachRule { _, role ->
            roles.add(role)
            false
        }
        roles.assert().containsExactly("r2", "r1")
        group.rules.assert().isSameAs(
            ((snapshot.app as DocumentState.Present).compiled.appPermission as Compiled.Ok).value
        )
    }

    @Test
    fun rolesAreScopedToTheRequestsSpace() {
        (load(reads(roles = setOf("r1"), spaceId = "s1")).layers[2] as LayerInput.Absent).reason
            .assert().isEqualTo(AbsentReason.NO_ROLE_IN_SPACE)
        val layer = load(reads(roles = setOf("r1@s1"), spaceId = "s1")).layers[2] as LayerInput.Loaded
        layer.groups.single().assert().isInstanceOf(AppPermissionGroup::class.java)
    }

    @Test
    fun anAppWithoutADocumentOrAValidIdHasNoAppPermission() {
        (load(reads(roles = setOf("r1"), appId = "unknown")).layers[2] as LayerInput.Absent).reason
            .assert().isEqualTo(AbsentReason.NO_APP_PERMISSION)
        val invalid = reads(roles = setOf("r1"), appId = "a:b")
        invalid.appId.assert().isNull()
        (load(invalid).layers[2] as LayerInput.Absent).reason.assert().isEqualTo(AbsentReason.NO_APP_PERMISSION)
    }

    @Test
    fun aBrokenAppPermissionOrGrantFailsTheRoleLayer() {
        load(
            reads(roles = setOf("r1"), appId = "broken")
        ).layers[2].assert().isInstanceOf(LayerInput.Unavailable::class.java)
        load(reads(roles = linkedSetOf("r1", "r3"))).layers[2].assert().isInstanceOf(LayerInput.Unavailable::class.java)
    }

    @Test
    fun aSnapshotOfAnotherAppFailsTheRoleLayer() {
        val reads = reads(roles = setOf("r1"))
        val other = compiledSnapshot(data, "broken", compiler)
        (SnapshotPolicySetLoader.load(reads, other).layers[2] as LayerInput.Unavailable).error
            .assert().isInstanceOf(IllegalStateException::class.java)
        val none = compiledSnapshot(data, null, compiler)
        SnapshotPolicySetLoader.load(reads, none).layers[2].assert().isInstanceOf(LayerInput.Unavailable::class.java)
    }

    @Test
    fun readsThatThrowFailTheirLayer() {
        val principal = mockk<CoSecPrincipal> {
            every { policies } throws IllegalStateException("policies")
            every { roles } throws IllegalStateException("roles")
        }
        val facts = LegacyFacts.authzRequest(EvaluateRequest(), SimpleSecurityContext(principal)).host as LegacyFacts
        val reads = SnapshotReads.of(facts)
        reads.appId.assert().isNull()
        val layers = load(reads).layers
        (layers[1] as LayerInput.Unavailable).error.message.assert().isEqualTo("policies")
        (layers[2] as LayerInput.Unavailable).error.message.assert().isEqualTo("roles")
    }

    @Test
    fun readsFollow70sOrder() {
        val principal = mockk<CoSecPrincipal> {
            every { policies } returns emptySet()
            every { roles } returns setOf("r1")
        }
        val request = mockk<Request> {
            every { spaceId } returns ""
            every { appId } throws IllegalStateException("app")
        }
        val reads = SnapshotReads.of(
            LegacyFacts.authzRequest(request, SimpleSecurityContext(principal)).host as LegacyFacts
        )
        (load(reads).layers[2] as LayerInput.Unavailable).error.message.assert().isEqualTo("app")
        verifyOrder {
            principal.policies
            principal.roles
            request.spaceId
            request.appId
        }
    }
}
