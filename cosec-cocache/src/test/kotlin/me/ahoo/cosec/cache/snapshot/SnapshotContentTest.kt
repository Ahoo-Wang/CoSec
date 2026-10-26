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

package me.ahoo.cosec.cache.snapshot

import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotFormatException
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.TreeMap

class SnapshotContentTest {
    private val publishedAt = Instant.parse("2026-10-21T08:00:00.123456Z")
    private fun tree(json: String): JsonNode = CoSecJsonSerializer.readTree(json)

    private fun document(body: String) =
        SnapshotDocument(1, 1, Instant.parse("2026-10-21T08:00:00Z"), body.encodeToByteArray())

    @Test
    fun writesPoliciesInIdOrderWithTheHeaderFirst() {
        val content = SnapshotContent.Policies(
            TreeMap(mapOf("b" to tree("""{"id":"b"}"""), "a" to tree("""{"id":"a","type":"global"}""")))
        )
        val document = content.write(7, publishedAt)
        document.revision.assert().isEqualTo(7L)
        document.publishedAt.assert().isEqualTo(Instant.parse("2026-10-21T08:00:00.123Z"))
        document.bodyBytes().decodeToString().assert().isEqualTo(
            """{"schema":1,"revision":7,"publishedAt":"2026-10-21T08:00:00.123Z",""" +
                """"policies":[{"id":"a","type":"global"},{"id":"b"}]}"""
        )
        // what the codec stores is what it reads back
        SnapshotDocumentCodec.decode(SnapshotDocumentCodec.encode(document)).assert().isEqualTo(document)
    }

    @Test
    fun writesAppsWithGrantsInKeyOrder() {
        val key = SnapshotKey.App("app")
        val content = SnapshotContent.App(
            key,
            tree("""{"id":"app"}"""),
            TreeMap(mapOf("viewer@team" to tree("""["p2"]"""), "admin" to tree("""["*"]"""))),
        )
        content.write(1, publishedAt).bodyBytes().decodeToString().assert().isEqualTo(
            """{"schema":1,"revision":1,"publishedAt":"2026-10-21T08:00:00.123Z",""" +
                """"appPermission":{"id":"app"},"grants":{"admin":["*"],"viewer@team":["p2"]}}"""
        )
    }

    @Test
    fun readsWhatItWrites() {
        val key = SnapshotKey.App("app")
        val written = SnapshotContent.App(key, tree("""{"id":"app"}"""), TreeMap(mapOf("r" to tree("""["p"]"""))))
            .write(3, publishedAt)
        val read = SnapshotContent.read(key, written) as SnapshotContent.App
        read.write(3, publishedAt).assert().isEqualTo(written)

        val policies = SnapshotContent.Policies(
            TreeMap(mapOf("p" to tree("""{"id":"p","x":[1,2]}""")))
        ).write(1, publishedAt)
        SnapshotContent.read(SnapshotKey.Policies, policies).write(1, publishedAt).assert().isEqualTo(policies)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"schema":1,"revision":1,"publishedAt":"2026-10-21T08:00:00Z"}""",
            """{"schema":1,"revision":1,"publishedAt":"2026-10-21T08:00:00Z","policies":{}}""",
            """{"schema":1,"revision":1,"publishedAt":"2026-10-21T08:00:00Z","policies":[{"name":"no id"}]}""",
            """{"schema":1,"revision":1,"publishedAt":"2026-10-21T08:00:00Z","policies":[{"id":"a"},{"id":"a"}]}""",
            """{"schema":1,"revision":1,"publishedAt":"2026-10-21T08:00:00Z","policies":[],"extra":1}""",
            """{"schema":1,"revision":1,"publishedAt":"2026-10-21T08:00:00Z","policies":[],"policies":[]}""",
            """[]""",
            """{""",
        ]
    )
    fun refusesMalformedPolicies(body: String) {
        assertThrows<SnapshotFormatException> { SnapshotContent.read(SnapshotKey.Policies, document(body)) }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """{"schema":1,"revision":1,"publishedAt":"2026-10-21T08:00:00Z","appPermission":{}}""",
            """{"schema":1,"revision":1,"publishedAt":"2026-10-21T08:00:00Z","grants":{}}""",
            """{"schema":1,"revision":1,"publishedAt":"2026-10-21T08:00:00Z","appPermission":[],"grants":{}}""",
            """{"schema":1,"revision":1,"publishedAt":"2026-10-21T08:00:00Z","appPermission":{},"grants":[]}""",
            """{"schema":1,"revision":1,"publishedAt":"2026-10-21T08:00:00Z","appPermission":{},"grants":{},"policies":[]}""",
        ]
    )
    fun refusesMalformedApps(body: String) {
        assertThrows<SnapshotFormatException> { SnapshotContent.read(SnapshotKey.App("app"), document(body)) }
    }

    @Test
    fun namesThePolicyId() {
        SnapshotContent.policyId(tree("""{"id":"p"}""")).assert().isEqualTo("p")
        SnapshotContent.policyId(tree("""{"id":1}""")).assert().isNull()
        SnapshotContent.policyId(tree("""{}""")).assert().isNull()
    }

    /** Review: untouched entries go back as they were stored, so numbers a tree would rewrite survive. */
    @Test
    fun writesStoredEntriesBackByteForByte() {
        val odd = """{"id":"p", "x": [1e400, 0.12345678901234567890123, 1.10],"y" : {"z":-0.0}}"""
        val grant = """[ "p1",  "p2" ]"""
        val policies = document("""{"policies":[$odd,{"id":"q"}]}""")
        val read = SnapshotContent.read(SnapshotKey.Policies, policies) as SnapshotContent.Policies
        // a writer changes another entry
        read.policies["r"] = tree("""{"id":"r"}""")
        val written = read.write(2, publishedAt).bodyBytes().decodeToString()
        written.assert().contains(odd).contains("""{"id":"q"}""")

        val key = SnapshotKey.App("app")
        val appPermission = """{"id":"app","n":1.10}"""
        val app = document("""{"appPermission":$appPermission,"grants":{"r":$grant}}""")
        val readApp = SnapshotContent.read(key, app) as SnapshotContent.App
        readApp.grants["s"] = tree("""["p3"]""")
        readApp.write(2, publishedAt).bodyBytes().decodeToString().assert()
            .contains(""""appPermission":$appPermission""")
            .contains(""""r":$grant""")
        SnapshotContent.treeOf(readApp.grants.getValue("r")).assert().isEqualTo(tree(grant))
    }

    /** Review: a grant key the compiler rejects would fail every request of the app. */
    @ParameterizedTest
    @ValueSource(strings = ["r@a@b", "r@", "@s", ""])
    fun refusesGrantKeysThatNameNoRoleInASpace(grantKey: String) {
        val body = """{"schema":1,"revision":1,"publishedAt":"2026-10-21T08:00:00Z",""" +
            """"appPermission":{"id":"app"},"grants":{"$grantKey":["p1"]}}"""
        assertThrows<SnapshotFormatException> { SnapshotContent.read(SnapshotKey.App("app"), document(body)) }
        SnapshotContent.isValidGrantKey(grantKey).assert().isFalse()
    }

    @Test
    fun acceptsGrantKeysOfRolesAndSpaces() {
        listOf("r", "r@s").forEach { SnapshotContent.isValidGrantKey(it).assert().describedAs(it).isTrue() }
    }

    /** Review: a document is bound to its key; app A's document under `app:B` must not be accepted. */
    @ParameterizedTest
    @ValueSource(strings = ["""{"id":"other"}""", """{}""", """{"id":1}"""])
    fun refusesAnAppPermissionOfAnotherApp(appPermission: String) {
        val body = """{"schema":1,"revision":1,"publishedAt":"2026-10-21T08:00:00Z",""" +
            """"appPermission":$appPermission,"grants":{}}"""
        assertThrows<SnapshotFormatException> { SnapshotContent.read(SnapshotKey.App("app"), document(body)) }
    }

    @Test
    fun refusesSectionsThatAreNeitherArraysNorObjects() {
        val body = """{"schema":1,"revision":1,"publishedAt":"2026-10-21T08:00:00Z","policies":1}"""
        assertThrows<SnapshotFormatException> { SnapshotContent.read(SnapshotKey.Policies, document(body)) }
        val app = """{"schema":1,"revision":1,"publishedAt":"2026-10-21T08:00:00Z","appPermission":[],"grants":{}}"""
        assertThrows<SnapshotFormatException> { SnapshotContent.read(SnapshotKey.App("app"), document(app)) }
    }

    /** Round 2 (M10): the id must be the string the key names; `1` under `app:1` is not `"1"`. */
    @Test
    fun refusesANumericIdUnderAMatchingKey() {
        val body = """{"schema":1,"revision":1,"publishedAt":"2026-10-21T08:00:00Z","appPermission":{"id":1},"grants":{}}"""
        assertThrows<SnapshotFormatException> { SnapshotContent.read(SnapshotKey.App("1"), document(body)) }
        val matching = """{"schema":1,"revision":1,"publishedAt":"2026-10-21T08:00:00Z","appPermission":{"id":"1"},"grants":{}}"""
        SnapshotContent.read(SnapshotKey.App("1"), document(matching)).key.assert().isEqualTo(SnapshotKey.App("1"))
    }
}
