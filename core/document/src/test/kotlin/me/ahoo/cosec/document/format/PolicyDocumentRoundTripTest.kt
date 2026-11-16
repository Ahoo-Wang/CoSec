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
 * to keep holding): for every snapshot document the generator makes and every one the repository holds, under every
 * kind that accepts it, the document the codec decodes writes back to bytes that decode to a document which writes the
 * same bytes, and a document the codec wrote is the same tree when it is read and written again. [PolicyDocument] has no
 * equality of its own, so "the same document" is what the format can see of it: its tree and the ids of its sections.
 */
class PolicyDocumentRoundTripTest {
    private val kinds = listOf(
        DocumentKind.PLATFORM to "",
        DocumentKind.TENANT to "acme",
        DocumentKind.APP to "order-service"
    )

    /** The document as each kind that accepts it reads it: the kind decides what is read (a tenant's binding names its tenant, ...). */
    private fun decodedByEachKind(node: JsonNode): List<PolicyDocument> {
        val bytes = CoSecJson.mapper.writeValueAsBytes(node)
        return kinds.mapNotNull { (kind, owner) ->
            runCatching { PolicyDocumentCodec.decode(kind, owner, bytes) }.getOrNull()
        }
    }

    private fun generated(): List<Pair<String, JsonNode>> {
        val random = Random(SEED)
        return (1..GENERATED).map { index ->
            val generator = FormatGenerator(random)
            val document = generator.generate(requireNotNull(DocumentFormats.snapshotDocumentSchema.root), "document")
            "generated #$index" to selfConsistentSnapshotDocument(document)
        }
    }

    private fun repository(): List<Pair<String, JsonNode>> =
        RepositoryDocuments.find(File(checkNotNull(System.getProperty("cosec.repository.root"))))
            .filter { it.file == DocumentFormats.snapshotDocumentSchema }
            .map { it.where to it.node }

    private fun roundTrip(where: String, first: PolicyDocument, sections: MutableSet<String>) {
        val written = PolicyDocumentCodec.encode(first)
        val again = PolicyDocumentCodec.decode(first.kind, first.owner, written)
        val rewritten = String(PolicyDocumentCodec.encode(again))
        rewritten.assert().describedAs("$where: encode(decode(encode(d))) is encode(d)").isEqualTo(String(written))
        val firstTree = PolicyDocumentCodec.tree(first)
        val againTree = PolicyDocumentCodec.tree(again)
        againTree.assert().describedAs("$where: the tree").isEqualTo(firstTree)
        again.policies.map { it.id }.assert().describedAs("$where: policy ids").isEqualTo(first.policies.map { it.id })
        again.bindings.map { it.id }.assert().describedAs("$where: binding ids").isEqualTo(first.bindings.map { it.id })
        again.roles.map { it.id }.assert().describedAs("$where: role ids").isEqualTo(first.roles.map { it.id })
        again.rateLimits.map { it.id }.assert().describedAs("$where: rate limit ids")
            .isEqualTo(first.rateLimits.map { it.id })
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
    }

    @Test
    fun `a document the codec wrote reads and writes back to the same bytes`() {
        val roundTripped = HashMap<DocumentKind, Int>()
        val sections = HashSet<String>()
        for ((where, node) in generated()) {
            val documents = decodedByEachKind(node)
            // The generator makes documents the format accepts: one that no kind reads is a failure, not a skip.
            documents.assert().describedAs("$where: no kind reads a generated document").isNotEmpty()
            documents.forEach {
                roundTrip("$where as ${it.kind}", it, sections)
                roundTripped.merge(it.kind, 1, Int::plus)
            }
        }
        // A repository document may be refused on purpose (a sample of an invalid document), so only how many are read is pinned.
        var repositoryRead = 0
        for ((where, node) in repository()) {
            val documents = decodedByEachKind(node)
            if (documents.isNotEmpty()) repositoryRead++
            documents.forEach {
                roundTrip("$where as ${it.kind}", it, sections)
                roundTripped.merge(it.kind, 1, Int::plus)
            }
        }
        // Not vacuous: each kind round-tripped documents, and between them every section of the format was present.
        DocumentKind.entries.forEach { kind ->
            (roundTripped[kind] ?: 0).assert().describedAs("documents round-tripped as $kind")
                .isGreaterThanOrEqualTo(MINIMUM_PER_KIND)
        }
        repositoryRead.assert().describedAs("repository documents read").isGreaterThanOrEqualTo(MINIMUM_REPOSITORY)
        sections.assert().containsExactlyInAnyOrder(
            "policies", "bindings", "roles", "rateLimits", "catalog", "limits", "attributes", "provenance", "boundary"
        )
    }

    private companion object {
        const val SEED = 20261117L
        const val GENERATED = 300

        // What the seed gives today is about 170 per kind and 45 repository documents; the margin is for the corpus to grow.
        const val MINIMUM_PER_KIND = 100
        const val MINIMUM_REPOSITORY = 40
    }
}
