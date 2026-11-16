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

package me.ahoo.cosec.document.format

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import java.io.File
import kotlin.random.Random

/**
 * The codec writes what it reads, and reads what it writes (RFC 0013 B5 refactors it by section, and this is what has
 * to keep holding): for every snapshot document the generator makes and every one the repository holds, the document the
 * codec decodes writes back to bytes that decode to a document which writes the same bytes, and a document the codec
 * wrote is the same tree when it is read and written again. [PolicyDocument] has no equality of its own, so "the same
 * document" is what the format can see of it: its tree and the ids of its sections.
 */
class PolicyDocumentRoundTripTest {
    private val kinds = listOf(
        DocumentKind.PLATFORM to "",
        DocumentKind.TENANT to "acme",
        DocumentKind.APP to "order-service"
    )

    /** The document as the first kind that accepts it reads it, as `SchemaConformanceTest` decodes one. */
    private fun decoded(node: JsonNode): PolicyDocument? {
        val bytes = CoSecJson.mapper.writeValueAsBytes(node)
        return kinds.firstNotNullOfOrNull { (kind, owner) ->
            runCatching {
                PolicyDocumentCodec.decode(
                    kind,
                    owner,
                    bytes
                )
            }.getOrNull()
        }
    }

    private fun corpus(): List<Pair<String, JsonNode>> {
        val random = Random(SEED)
        val generated = (1..GENERATED).map { index ->
            val generator = FormatGenerator(random)
            val document = generator.generate(requireNotNull(DocumentFormats.snapshotDocumentSchema.root), "document")
            // Ids the codec requires unique, and the tenant an app document's principals must name, as the conformance test makes them.
            document.get("policies")?.values()?.forEachIndexed { at, entry ->
                (entry as tools.jackson.databind.node.ObjectNode).put("id", "${entry.get("id").asString()}-$at")
            }
            if (document.has("catalog")) {
                document.get("bindings")?.values()?.map { it.get("subject") }
                    ?.filterIsInstance<tools.jackson.databind.node.ObjectNode>()
                    ?.filter { it.has("principal") && !it.has("tenant") }?.forEach { it.put("tenant", "acme") }
            }
            "generated #$index" to document
        }
        val repository = RepositoryDocuments.find(File(checkNotNull(System.getProperty("cosec.repository.root"))))
            .filter { it.file == DocumentFormats.snapshotDocumentSchema }
            .map { it.where to it.node }
        return generated + repository
    }

    @Test
    fun `a document the codec wrote reads and writes back to the same bytes`() {
        var roundTripped = 0
        val sections = HashSet<String>()
        for ((where, node) in corpus()) {
            val first = decoded(node) ?: continue
            val written = PolicyDocumentCodec.encode(first)
            val again = PolicyDocumentCodec.decode(first.kind, first.owner, written)
            val rewritten = PolicyDocumentCodec.encode(again)
            String(
                rewritten
            ).assert().describedAs("$where: encode(decode(encode(d))) is encode(d)").isEqualTo(String(written))
            PolicyDocumentCodec.tree(
                again
            ).assert().describedAs("$where: the tree").isEqualTo(PolicyDocumentCodec.tree(first))
            again.policies.map { it.id }.assert().describedAs(
                "$where: policy ids"
            ).isEqualTo(first.policies.map { it.id })
            again.bindings.map { it.id }.assert().describedAs(
                "$where: binding ids"
            ).isEqualTo(first.bindings.map { it.id })
            again.roles.map { it.id }.assert().describedAs("$where: role ids").isEqualTo(first.roles.map { it.id })
            again.rateLimits.map { it.id }.assert().describedAs(
                "$where: rate limit ids"
            ).isEqualTo(first.rateLimits.map { it.id })
            again.kind.assert().isEqualTo(first.kind)
            again.revision.assert().isEqualTo(first.revision)
            if (first.policies.isNotEmpty()) sections += "policies"
            if (first.bindings.isNotEmpty()) sections += "bindings"
            if (first.roles.isNotEmpty()) sections += "roles"
            if (first.rateLimits.isNotEmpty()) sections += "rateLimits"
            if (first.catalog != null) sections += "catalog"
            if (first.limits != null) sections += "limits"
            if (first.attributes != null) sections += "attributes"
            if (first.provenance != null) sections += "provenance"
            if (first.boundary != null) sections += "boundary"
            roundTripped++
        }
        // Not vacuous: documents were read, and between them every section of the format was present at least once.
        roundTripped.assert().describedAs("documents round-tripped").isGreaterThan(GENERATED / 2)
        sections.assert().containsExactlyInAnyOrder(
            "policies", "bindings", "roles", "rateLimits", "catalog", "limits", "attributes", "provenance", "boundary"
        )
    }

    private companion object {
        const val SEED = 20261116L
        const val GENERATED = 300
    }
}
