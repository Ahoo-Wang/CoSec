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

import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.engine.LayerInput
import me.ahoo.cosec.parity.LegacyData
import me.ahoo.cosec.parity.PUBLISHED_AT
import me.ahoo.cosec.parity.appPermissionJson
import me.ahoo.cosec.parity.permissionJson
import me.ahoo.cosec.parity.policyJson
import me.ahoo.cosec.parity.snapshotDocument
import me.ahoo.cosec.snapshot.SnapshotFormatException
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.SnapshotUnavailableException
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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

    /**
     * A document is bound to its key: app A's document copied under app B would otherwise grant B's principals
     * what A grants. Any other id, or none, fails the whole document.
     */
    @ParameterizedTest
    @ValueSource(strings = [""""id":"other",""", """""", """"id":7,"""])
    fun rejectsAnAppDocumentOfAnotherApp(id: String) {
        val appPermission = """{$id"condition":{"all":{}},"groups":[]}"""
        assertThrows<SnapshotFormatException> { app(""""appPermission":$appPermission,"grants":{}""") }
        app(""""appPermission":$catalog,"grants":{}""").appId.assert().isEqualTo("app")
        assertThrows<SnapshotFormatException> { app(""""appPermission":$catalog,"grants":{}""", appId = "other") }
    }

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
            // A repeated section must never replace one that holds a deny.
            """"policies":[{"id":"deny","name":"deny","type":"global","tenantId":"t","statements":[{"effect":"deny","action":"*"}]}],"policies":[]""",
            """"policies":[{"id":"a","id":"b","name":"a","type":"custom","tenantId":"t"}]""",
            """"policies":[{"id":"a","name":"a","type":"global","type":"custom","tenantId":"t"}]""",
            """"policies":[],"schema":1""",
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
            """"appPermission":{"id":"app","condition":{"all":{"negate":true}}},"appPermission":{"id":"app"}""",
            """"appPermission":{"id":"app"},"grants":{"r1":["*"]},"grants":{}""",
            """"appPermission":{"id":"app","groups":[],"groups":[]}""",
        ]
    )
    fun aMalformedAppDocumentFailsWhole(sections: String) {
        assertThrownBy<SnapshotFormatException> { app(sections) }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"schema":1,"revision":2,"publishedAt":"2026-10-21T08:00:00Z","policies":[]}""",
            """{"schema":2,"revision":1,"publishedAt":"2026-10-21T08:00:00Z","policies":[]}""",
            """{"schema":"1","revision":1,"publishedAt":"2026-10-21T08:00:00Z","policies":[]}""",
            """{"schema":1,"revision":1,"publishedAt":"2026-10-21T08:00:01Z","policies":[]}""",
            """{"schema":1,"revision":1,"publishedAt":"yesterday","policies":[]}""",
            """{"schema":1,"revision":1,"publishedAt":{},"policies":[]}""",
        ]
    )
    fun aHeaderThatDiffersFromTheDecodedOneFails(body: String) {
        val document = me.ahoo.cosec.snapshot.SnapshotDocument(1, 1, PUBLISHED_AT, body.encodeToByteArray())
        assertThrownBy<SnapshotFormatException> { compiler.policies.compile(SnapshotKey.Policies, document) }
    }

    @Test
    fun theHeaderMayBeLeftOutOfTheBody() {
        val document = me.ahoo.cosec.snapshot.SnapshotDocument(
            1,
            1,
            PUBLISHED_AT,
            """{"policies":[]}""".encodeToByteArray()
        )
        compiler.policies.compile(SnapshotKey.Policies, document).byId.assert().isEmpty()
    }

    /** A mapper whose policy and app permission decoding fails with [error], as a matcher factory's could. */
    private fun failingCompiler(error: () -> Throwable): PolicySnapshotCompiler {
        val module = tools.jackson.databind.module.SimpleModule()
            .addDeserializer(Policy::class.java, Failing(Policy::class.java, error))
            .addDeserializer(AppPermission::class.java, Failing(AppPermission::class.java, error))
        return PolicySnapshotCompiler(tools.jackson.module.kotlin.jsonMapper { addModule(module) })
    }

    private class Failing<T : Any>(type: Class<T>, private val error: () -> Throwable) :
        tools.jackson.databind.deser.std.StdDeserializer<T>(type) {
        override fun deserialize(
            p: tools.jackson.core.JsonParser,
            ctxt: tools.jackson.databind.DeserializationContext
        ): T =
            throw error()
    }

    @Test
    fun aLinkageErrorFailsOnlyItsEntry() {
        val compiler = failingCompiler { ExceptionInInitializerError("missing hint") }
        val document = snapshotDocument(1, """"policies":[${policyJson("a")}]""")
        val broken = compiler.policies.compile(SnapshotKey.Policies, document).byId.getValue("a") as Compiled.Broken
        broken.error.cause.assert().isInstanceOf(ExceptionInInitializerError::class.java)
        val app = compiler.app.compile(SnapshotKey.App("app"), snapshotDocument(1, """"appPermission":$catalog"""))
        (app.appPermission as Compiled.Broken).error.cause.assert().isInstanceOf(LinkageError::class.java)
    }

    @Test
    fun aVirtualMachineErrorIsNotSwallowed() {
        val compiler = failingCompiler { StackOverflowError("deep") }
        assertThrownBy<StackOverflowError> {
            compiler.policies.compile(SnapshotKey.Policies, snapshotDocument(1, """"policies":[${policyJson("a")}]"""))
        }
    }

    @Test
    fun compiledCollectionsCannotBeChanged() {
        val compiled = policies(policyJson("g", "global"))
        assertThrownBy<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (compiled.byId as MutableMap<String, Compiled<PolicyGroup>>).clear()
        }
        assertThrownBy<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            ((compiled.globalLayer as LayerInput.Loaded).groups as MutableList<Any>).clear()
        }
        val app = app(""""appPermission":$catalog,"grants":{"r1":["p1"]}""")
        assertThrownBy<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (app.grants.getValue("") as MutableMap<String, Any>).clear()
        }
    }
}
