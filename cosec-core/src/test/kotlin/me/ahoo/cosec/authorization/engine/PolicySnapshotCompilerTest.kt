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

import me.ahoo.cosec.engine.LayerInput
import me.ahoo.cosec.snapshot.SnapshotFormatException
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.SnapshotUnavailableException
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class PolicySnapshotCompilerTest {
    private val compiler = PolicySnapshotCompiler()
    private val unknownCondition = """{"noSuchMatcher":{}}"""

    private fun policies(vararg policies: String) =
        compiler.policies.compile(
            SnapshotKey.Policies,
            snapshotDocument(7, """"policies":[${policies.joinToString(",")}]""")
        )

    private fun app(sections: String, appId: String = "app") =
        compiler.app.compile(SnapshotKey.App(appId), snapshotDocument(3, sections))

    private val catalog = appPermissionJson("app", listOf(permissionJson("p1"), permissionJson("p2")).joinToString(","))

    @Test
    fun compilesPoliciesInDocumentOrder() {
        val compiled = policies(policyJson("b", "global"), policyJson("a", "custom"), policyJson("c", "global"))
        compiled.revision.assert().isEqualTo(7)
        val global = compiled.globalLayer as LayerInput.Loaded
        global.groups.map { it.id }.assert().containsExactly("b", "c")
        compiled.byId.keys.assert().containsExactlyInAnyOrder("a", "b", "c")
        // The groups are the policies' own cached ones, shared by every request.
        val a = (compiled.byId.getValue("a") as Compiled.Ok).value
        a.assert().isSameAs((a.policy as me.ahoo.cosec.policy.PolicyData).engineGroup())
    }

    @Test
    fun withoutGlobalPoliciesTheGlobalLayerIsLoadedAndEmpty() {
        policies(policyJson("a")).globalLayer.assert().isSameAs(PolicySnapshotCompiler.NO_GLOBAL_POLICIES)
        policies().byId.assert().isEmpty()
    }

    @Test
    fun aBrokenPolicyFailsOnlyItself() {
        val compiled = policies(policyJson("a", "custom", condition = unknownCondition), policyJson("b", "global"))
        val broken = compiled.byId.getValue("a") as Compiled.Broken
        broken.error.assert().isInstanceOf(SnapshotUnavailableException::class.java)
        broken.error.cause.assert().isNotNull()
        (compiled.globalLayer as LayerInput.Loaded).groups.map { it.id }.assert().containsExactly("b")
    }

    @ParameterizedTest
    @ValueSource(strings = ["\"type\":\"global\",", "\"type\":\"nonsense\",", "\"type\":7,", ""])
    fun aBrokenPolicyThatMayBeGlobalFailsTheGlobalLayer(type: String) {
        val broken = """{"id":"a",$type"name":"a","tenantId":"t","condition":$unknownCondition}"""
        val compiled = policies(broken, policyJson("b", "global"))
        val layer = compiled.globalLayer as LayerInput.Unavailable
        layer.error.assert().isSameAs((compiled.byId.getValue("a") as Compiled.Broken).error)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """"policies":[{"name":"x"}]""",
            """"policies":[{"id":1}]""",
            """"policies":{}""",
            """"other":[]""",
            """"policies":[],"extra":1""",
        ]
    )
    fun aMalformedPoliciesDocumentFailsWhole(sections: String) {
        assertThrownBy<SnapshotFormatException> {
            compiler.policies.compile(SnapshotKey.Policies, snapshotDocument(1, sections))
        }
    }

    @Test
    fun aMissingPoliciesSectionFails() {
        assertThrownBy<SnapshotFormatException> {
            val document = me.ahoo.cosec.snapshot.SnapshotDocument(
                1,
                1,
                PUBLISHED_AT,
                """{"schema":1}""".encodeToByteArray()
            )
            compiler.policies.compile(SnapshotKey.Policies, document)
        }
    }

    @Test
    fun duplicatePoliciesFail() {
        assertThrownBy<SnapshotFormatException> { policies(policyJson("a"), policyJson("a", "global")) }
    }

    @Test
    fun anUnknownSchemaFails() {
        assertThrownBy<SnapshotFormatException> {
            compiler.policies.compile(SnapshotKey.Policies, snapshotDocument(1, """"policies":[]""", schema = 2))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "[]", "{", "{\"policies\":[]} {}", "{\"policies\":[}"])
    fun invalidJsonFails(body: String) {
        val document = me.ahoo.cosec.snapshot.SnapshotDocument(1, 1, PUBLISHED_AT, body.encodeToByteArray())
        assertThrownBy<SnapshotFormatException> { compiler.policies.compile(SnapshotKey.Policies, document) }
    }

    @Test
    fun keysOfTheOtherKindAreRejected() {
        assertThrownBy<IllegalArgumentException> {
            compiler.policies.compile(SnapshotKey.App("app"), snapshotDocument(1, """"policies":[]"""))
        }
        assertThrownBy<IllegalArgumentException> {
            compiler.app.compile(SnapshotKey.Policies, snapshotDocument(1, """"appPermission":$catalog"""))
        }
    }

    @Test
    fun compilesGrantsBySpaceAndRoleInOrder() {
        val compiled = app(""""appPermission":$catalog,"grants":{"r1":["p2","p1","ghost"],"r1@s1":["*"],"r2@s1":[]}""")
        compiled.revision.assert().isEqualTo(3)
        compiled.appId.assert().isEqualTo("app")
        val rules = (compiled.appPermission as Compiled.Ok).value
        rules.shared.assert().isTrue()
        val r1 = (compiled.grants.getValue("").getValue("r1") as Compiled.Ok).value
        r1.id.assert().isEqualTo("r1")
        r1.permissions.toList().assert().isEqualTo(LegacyData.decodeGrants("""["p2","p1","ghost"]""").toList())
        compiled.grants.getValue("s1").keys.assert().containsExactlyInAnyOrder("r1", "r2")
    }

    @Test
    fun withoutGrantsTheAppHasNone() {
        app(""""appPermission":$catalog""").grants.assert().isEmpty()
    }

    @Test
    fun aBrokenAppPermissionOrGrantFailsOnlyItself() {
        val compiled = app(
            """"appPermission":${appPermissionJson(
                "app",
                permissionJson("p1"),
                unknownCondition
            )},"grants":{"r1":{"a":1},"r2":["p1"]}"""
        )
        (compiled.appPermission as Compiled.Broken).error.assert().isInstanceOf(
            SnapshotUnavailableException::class.java
        )
        (compiled.grants.getValue("").getValue("r1") as Compiled.Broken).error.cause.assert().isNotNull()
        compiled.grants.getValue("").getValue("r2").assert().isInstanceOf(Compiled.Ok::class.java)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """"grants":{"r1":[]}""",
            """"appPermission":[]""",
            """"appPermission":{"id":"app"},"grants":[]""",
            """"appPermission":{"id":"app"},"grants":{"r1@":[]}""",
            """"appPermission":{"id":"app"},"grants":{"a@b@c":[]}""",
            """"appPermission":{"id":"app"},"grants":{"":[]}""",
            """"appPermission":{"id":"app"},"grants":{"r1":[],"r1":["x"]}""",
            """"appPermission":{"id":"app"},"other":1""",
        ]
    )
    fun aMalformedAppDocumentFailsWhole(sections: String) {
        assertThrownBy<SnapshotFormatException> { app(sections) }
    }
}
