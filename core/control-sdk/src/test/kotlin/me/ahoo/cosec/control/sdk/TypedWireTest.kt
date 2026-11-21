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

package me.ahoo.cosec.control.sdk

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

/**
 * `CTL-76`: the typed objects say what the wire says. Each document entry reads exactly the document format's members
 * (`schema/8.0/`), and every tenant and app document of the wiki's samples reads and writes back as the same document;
 * a change answer's members are those of `contract/control/change.json`, each read or named as left out; and the closed
 * sets are the wire's.
 */
class TypedWireTest {
    private val root: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("settings.gradle.kts")) }

    private fun json(path: String): JsonNode = CoSecJson.mapper.readTree(root.resolve(path).readText())

    private fun JsonNode.schemaMembers(vararg path: String): Set<String> =
        path.fold(this) { node, name -> node.get(name) }.get("properties").propertyNames().toSet()

    @Test
    fun `each document entry reads exactly the members of the document format - CTL-76`() {
        val document = json("schema/8.0/cosec-snapshot-document.schema.json").get("definitions")
        val binding = json("schema/8.0/cosec-binding.schema.json")
        val catalog = json("schema/8.0/cosec-catalog.schema.json")
        val schema = mapOf(
            "policy" to document.schemaMembers("policy"),
            "statement" to document.schemaMembers("statement"),
            "onMissingConfirmed" to document.schemaMembers("statement", "properties", "onMissingConfirmed"),
            "resource" to document.get("resource").get("anyOf").first { it.has("properties") }.schemaMembers(),
            "binding" to binding.schemaMembers(),
            "principalSubject" to binding.get("definitions").schemaMembers("principalSubject"),
            "roleSubject" to binding.get("definitions").schemaMembers("roleSubject"),
            "role" to document.schemaMembers("tenantRole") + document.schemaMembers("appRole"),
            "catalog" to catalog.schemaMembers(),
            "group" to catalog.get("definitions").schemaMembers("group"),
            "permission" to catalog.get("definitions").schemaMembers("permission"),
            "document" to document.schemaMembers("tenantDocument") + document.schemaMembers("appDocument"),
        )
        DocumentJson.MEMBERS.assert().isEqualTo(schema)
    }

    @Test
    fun `every tenant and app document of the wiki reads and writes back as the same document - CTL-76`() {
        val samples = Files.walk(root.resolve("wiki/examples")).use { paths ->
            paths.filter { it.extension == "json" && it.parent.name in KINDS && it.parent.parent.name.startsWith("documents") }
                .toList()
        }
        samples.size.assert().isGreaterThan(10)
        samples.forEach { sample ->
            val kind = KINDS.getValue(sample.parent.name)
            val owner = URLDecoder.decode(sample.nameWithoutExtension, Charsets.UTF_8)
            val original = CoSecJson.mapper.readTree(sample.readText()) as ObjectNode
            // The header is the control plane's: what a published document says it is, not what it holds.
            original.remove("provenance")
            val content = DocumentJson.content(original)
            val written = DocumentJson.document(content, original.get("revision").asLong())
            val expected = PolicyDocumentCodec.decode(kind, owner, original).at(original.get("revision").asLong(), Instant.EPOCH)
            PolicyDocumentCodec.tree(PolicyDocumentCodec.decode(kind, owner, written)).assert().describedAs(sample.toString())
                .isEqualTo(PolicyDocumentCodec.tree(expected))
            DocumentJson.content(written).assert().describedAs(sample.toString()).isEqualTo(content)
        }
    }

    @Test
    fun `a document entry with a member this version does not know does not read - CTL-76`() {
        val later = """{"id":"p","statements":[{"effect":"deny","action":"order:delete","unless":{}}]}"""
        assertThrownBy<SnapshotFormatException> { DocumentJson.policy(CoSecJson.mapper.readTree(later)) }
            .hasMessageContaining("a statement").hasMessageNotContaining("unless")
        val known = """{"id":"p","statements":[{"effect":"deny","action":"order:delete"}]}"""
        DocumentJson.policy(CoSecJson.mapper.readTree(known)).statements.single().effect.assert().isEqualTo(Effect.DENY)
    }

    @Test
    fun `a change answer reads the members of its contract file, each read or named as left out - CTL-76`() {
        val contract = paths(json("contract/control/change.json"), "")
        (AnswerJson.CHANGE_READ intersect AnswerJson.CHANGE_UNREAD).assert().isEmpty()
        (AnswerJson.CHANGE_READ + AnswerJson.CHANGE_UNREAD).assert().isEqualTo(contract)
    }

    /** The dotted paths of a contract file's members: an array's items' members under the array's name. */
    private fun paths(members: JsonNode, prefix: String): Set<String> = members.properties().flatMap { (name, member) ->
        val path = prefix + name
        val nested = member.get("members") ?: member.get("items")?.get("members")
        setOf(path) + (nested?.let { paths(it, "$path.") } ?: emptySet())
    }.toSet()

    @Test
    fun `the closed sets are the wire's - CTL-76`() {
        val vocabularies = json("contract/control/vocabularies.json")
        fun wire(name: String) = vocabularies.get(name).values().map { it.asString() }
        ChangeState.KNOWN.map { it.text }.assert().isEqualTo(wire("state"))
        ChangeKind.KNOWN.map { it.text }.assert().isEqualTo(wire("kind"))
        Severity.KNOWN.map { it.text }.assert().isEqualTo(wire("severity"))
        EscalationReason.KNOWN.map { it.text }.assert().isEqualTo(wire("escalationReason"))
        val definitions = json("schema/8.0/definitions.schema.json").get("definitions")
        Effect.KNOWN.map { it.text }.assert().isEqualTo(definitions.get("effect").get("enum").values().map { it.asString() })
        val statement = json("schema/8.0/cosec-snapshot-document.schema.json").get("definitions").get("statement")
        OnMissing.KNOWN.map { it.text }
            .assert().isEqualTo(statement.get("properties").get("onMissing").get("enum").values().map { it.asString() })
    }

    @Test
    fun `a value of a closed set this version does not know keeps its text`() {
        listOf(
            ChangeKind.of("MERGE"), Severity.of("INFO"), EscalationReason.of("LATER"), ConflictKind.of("LATER"),
            ApplyMode.of("PATCH"), Effect.of("audit"), OnMissing.of("permit"),
        ).forEach {
            it.isUnknown.assert().isTrue()
            it.toString().assert().isEqualTo(it.text)
        }
        ConflictKind.of("SLOT_HELD").assert().isSameAs(ConflictKind.SLOT_HELD)
        ConflictKind.of("SLOT_HELD").assert().isNotEqualTo(ChangeState.of("SLOT_HELD"))
    }

    private companion object {
        val KINDS = mapOf("tenants" to DocumentKind.TENANT, "apps" to DocumentKind.APP)
    }
}
