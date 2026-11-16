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

package me.ahoo.cosec.document

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode
import java.time.Instant

/**
 * [PolicyDocument.at] passes on every member but the header. Two fixtures between them set every constructor parameter to
 * something other than its default (a catalog is an app document's, limits and attributes the platform's), and the
 * constructor's parameter count is pinned: a member added to [PolicyDocument] fails here until the fixtures set it and
 * `at` passes it on. The check compares what a store would hold: the encodings, with the header taken out.
 */
class PolicyDocumentAtTest {
    private val rule = """{"id":"login","action":"http:POST","resource":"http:path:/login","key":"environment.ip",""" +
        """"permitsPerSecond":0.5,"windowSeconds":60,"strictFailure":true}"""
    private val provenance = """"provenance":{"parent":{"revision":2,"digest":"$DIGEST"},"change":"0QH8K3",""" +
        """"kind":"PUBLISH","publishedBy":{"tenant":"(platform)","principal":"alice"},""" +
        """"approvedBy":[{"tenant":"(platform)","principal":"ops-1"}],"shadow":{"window":"0QH8M1","skipped":false}}"""

    private val platform = decode(
        DocumentKind.PLATFORM,
        "",
        """"boundaries":{"default":"b"},"limits":{"maxNodes":32,"maxCost":100},""" +
            """"attributes":[{"path":"ext.geo.region","type":"string"}],""" +
            """"policies":[{"id":"b","boundary":true,"statements":[]},""" +
            """{"id":"g","statements":[{"effect":"deny","action":"http:*"}]}],""" +
            """"bindings":[{"id":"g","subject":"*","app":"*","space":"*","policy":"g"}],""" +
            """"rateLimits":[$rule],$provenance""",
    )
    private val app = decode(
        DocumentKind.APP,
        "order",
        """"catalog":{"groups":[{"name":"order","permissions":[{"id":"read"}]}]},""" +
            """"roles":[{"id":"clerk","permissions":["read"]}],""" +
            """"policies":[{"id":"own","statements":[{"effect":"allow","action":"order:read"}]}],""" +
            """"bindings":[{"id":"o","subject":{"role":"clerk"},"app":"order","space":"*","policy":"own"}],""" +
            """"rateLimits":[$rule],$provenance""",
    )

    private fun decode(kind: DocumentKind, owner: String, body: String): PolicyDocument =
        PolicyDocumentCodec.decode(
            kind,
            owner,
            """{"schema":2,"revision":3,"publishedAt":"2026-10-26T00:00:00Z",$body}""".encodeToByteArray(),
        )

    /** The document as a store holds it, without its header. */
    private fun stored(document: PolicyDocument): ObjectNode =
        (CoSecJson.mapper.readTree(PolicyDocumentCodec.encode(document)) as ObjectNode).apply {
            remove("revision")
            remove("publishedAt")
        }

    @Test
    fun `the fixtures set every constructor parameter`() {
        // Pinned: a new member fails here until both the fixtures below and PolicyDocument.at are visited.
        PolicyDocument::class.java.declaredConstructors.minOf { it.parameterCount }.assert().isEqualTo(PARAMETERS)
        val fixtures = listOf(platform, app)
        fixtures.map { it.kind }.assert().containsExactly(DocumentKind.PLATFORM, DocumentKind.APP)
        fixtures.all { it.revision == 3L && it.publishedAt == AT && it.provenance != null }.assert().isTrue()
        fixtures.all { it.policies.isNotEmpty() && it.bindings.isNotEmpty() && it.rateLimits.isNotEmpty() }.assert().isTrue()
        app.owner.assert().isEqualTo("order")
        app.catalog.assert().isNotNull()
        app.roles.assert().isNotEmpty()
        platform.boundary.assert().isNotNull()
        platform.limits.assert().isNotNull()
        platform.attributes.assert().isNotNull()
    }

    @Test
    fun `at changes the revision and the time it is published, and nothing else`() {
        val later = Instant.parse("2026-11-01T00:00:00Z")
        listOf(platform, app).forEach { document ->
            val moved = document.at(9, later)
            moved.revision.assert().isEqualTo(9L)
            moved.publishedAt.assert().isEqualTo(later)
            stored(moved).assert().isEqualTo(stored(document))
            SnapshotDocuments.of(moved).revision.assert().isEqualTo(9L)
        }
    }

    private companion object {
        /** kind, owner, revision, publishedAt, policies, bindings, catalog, boundary, provenance, limits, attributes, rateLimits, roles. */
        const val PARAMETERS = 13
        val AT: Instant = Instant.parse("2026-10-26T00:00:00Z")
        const val DIGEST = "SHA-256:9f2c0b1e6a6d1f2c3b4a5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f809102"
    }
}
