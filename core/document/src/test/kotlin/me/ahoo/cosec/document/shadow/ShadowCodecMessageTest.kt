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

@file:OptIn(me.ahoo.cosec.annotations.InternalCoSecApi::class, me.ahoo.cosec.annotations.ExperimentalCoSecApi::class)

package me.ahoo.cosec.document.shadow

import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.spi.shadow.InstalledDocument
import me.ahoo.cosec.spi.shadow.InstanceStatus
import me.ahoo.cosec.spi.shadow.ShadowOutcome
import me.ahoo.cosec.spi.shadow.ShadowReport
import me.ahoo.cosec.spi.shadow.ShadowSample
import me.ahoo.cosec.spi.shadow.StoredCandidate
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKeyText
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.time.Instant

/**
 * A stored value that does not decode is logged by whoever reads it (the control API logs it at ERROR): its message
 * quotes nothing of the value unescaped or unbounded, and carries no parser exception, whose message quotes the bytes
 * (#1691's rule for the identity header, here for stored values).
 */
class ShadowCodecMessageTest {
    private fun refused(text: String): SnapshotFormatException =
        assertThrows<SnapshotFormatException> { ShadowCodec.decodeReport(text.encodeToByteArray()) }

    private fun SnapshotFormatException.isSafe() {
        val text = message.orEmpty()
        text.none { it.isISOControl() }.assert().describedAs(text).isTrue()
        // A message of its own quotes a few bounded values; one passed on from the document format is bounded whole.
        text.length.assert().isLessThan(SnapshotKeyText.MAX_MESSAGE + SnapshotKeyText.MAX_QUOTED)
        cause.assert().isNull()
    }

    @Test
    fun `bytes that are not JSON are named by where they fail, never by what they hold`() {
        val refused = refused("{\"x\n2026-11-19 ERROR forged\u0007" + "y".repeat(5_000))
        refused.isSafe()
        refused.message.assert().matches("A shadow value is not JSON at line \\d+, column \\d+\\.")
    }

    @Test
    fun `unknown members are quoted escaped, bounded and at most three`() {
        // JSON escapes: the decoded name holds the line break and the bell.
        val long = "x\\n2026-11-19 ERROR forged\\u0007" + "y".repeat(5_000)
        val refused = refused("""{"$long":1,"a\u0001":2,"b":3,"c":4,"d":5}""")
        refused.isSafe()
        refused.message.assert()
            .contains("x\\u000A2026-11-19 ERROR forged\\u0007").contains(" characters)").contains("and 2 more")
    }

    @Test
    fun `no bytes at all are refused as such`() {
        refused("").isSafe()
    }

    @Test
    fun `a value of the wrong type is quoted escaped and bounded`() {
        refused("[\"x\\u0007" + "y".repeat(5_000) + "\"]").isSafe()
    }

    @Test
    fun `a stored value broken anywhere is refused with a message that quotes it escaped and bounded`() {
        val now = Instant.parse("2026-11-01T00:00:00Z")
        val document = SnapshotDocuments.read(
            """{"schema":2,"revision":5,"publishedAt":"2026-11-01T00:00:00Z","policies":[],"bindings":[]}""".encodeToByteArray()
        )
        val report = ShadowReport(
            "w",
            "i1",
            mapOf(ShadowOutcome.NARROWED to 2L),
            mapOf(
                ShadowOutcome.NARROWED to listOf(ShadowSample("acme", "bob", "order", "", "http:GET", "o", "a[0]", null))
            ),
            setOf(4L),
            now,
            null,
        )
        val status = InstanceStatus(
            "i1",
            now,
            mapOf(SnapshotKey.Tenant("acme") to InstalledDocument(4, Duration.ofMillis(250)))
        )
        val decoders: List<Pair<ByteArray, (ByteArray) -> Any>> = listOf(
            ShadowCodec.encode(StoredCandidate("w", 4, document)) to ShadowCodec::decodeCandidate,
            ShadowCodec.encodeReport(report) to ShadowCodec::decodeReport,
            ShadowCodec.encodeStatuses(mapOf("i1" to status)) to ShadowCodec::decodeStatuses,
        )
        var refusals = 0
        for ((valid, decode) in decoders) {
            for (text in brokenEverywhere(valid)) {
                val refused = runCatching { decode(text.encodeToByteArray()) }.exceptionOrNull() ?: continue
                (refused as SnapshotFormatException).isSafe()
                refusals++
            }
        }
        refusals.assert().isGreaterThan(50)
    }

    /**
     * Every way [valid] breaks at one place: each value replaced by a long string holding a line break and control
     * characters, a negative number, an array, an object, and each member renamed to such a string.
     */
    private fun brokenEverywhere(valid: ByteArray): List<String> {
        val bad = "x\n2026-11-19 ERROR forged\u0007" + "y".repeat(5_000)
        val replacements: List<() -> JsonNode> = listOf(
            { JsonNodeFactory.instance.stringNode(bad) },
            { JsonNodeFactory.instance.numberNode(-1) },
            { JsonNodeFactory.instance.arrayNode() },
            { JsonNodeFactory.instance.objectNode() },
        )
        val root = CoSecJson.mapper.readTree(valid)
        val broken = ArrayList<String>()
        fun visit(path: List<Any>, node: JsonNode) {
            if (path.isNotEmpty()) {
                replacements.forEach { make -> broken += edit(root, path) { parent, at -> parent.put(at, make()) } }
                (path.last() as? String)?.let { name ->
                    broken += edit(root, path) { parent, _ -> (parent as ObjectNode).rename(name, bad) }
                }
            }
            when {
                node.isObject -> node.properties().forEach { (name, child) -> visit(path + name, child) }
                node.isArray -> node.forEachIndexed { index, child -> visit(path + index, child) }
            }
        }
        visit(emptyList(), root)
        return broken
    }

    private fun edit(root: JsonNode, path: List<Any>, change: (JsonNode, Any) -> Unit): String {
        val copy = root.deepCopy()
        val parent = path.dropLast(
            1
        ).fold(copy) { node, step -> if (step is String) node.get(step) else node.get(step as Int) }
        change(parent, path.last())
        return CoSecJson.mapper.writeValueAsString(copy)
    }

    private fun JsonNode.put(at: Any, value: JsonNode) {
        if (at is String) (this as ObjectNode).set(at, value) else (this as ArrayNode).set(at as Int, value)
    }

    private fun ObjectNode.rename(name: String, to: String) {
        val value = remove(name)
        set(to, value)
    }
}
