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
import me.ahoo.cosec.document.DocumentJson.actor
import me.ahoo.cosec.document.DocumentJson.factory
import me.ahoo.cosec.document.DocumentJson.nonEmpty
import tools.jackson.databind.node.ObjectNode

/** A document's provenance (RFC 0007 §10.2), both ways. */
internal object ProvenanceCodec {
    private val PROVENANCE_FIELDS = arrayOf("parent", "change", "kind", "publishedBy", "approvedBy", "shadow")

    internal fun decode(node: DocumentNode): Provenance {
        node.requireObject(*PROVENANCE_FIELDS)
        val kind = node.required("kind")
        return Provenance(
            parent = node.field("parent")?.let { parent ->
                parent.requireObject("revision", "digest")
                val revision = parent.required("revision")
                val digest = parent.required("digest")
                ParentRevision(
                    revision.long().takeIf { it > 0 } ?: revision.fail("must be positive"),
                    digest.text().takeIf { ParentRevision.DIGEST.matches(it) } ?: digest.fail("is `SHA-256:` and 64 lowercase hex digits"),
                )
            },
            change = node.required("change").nonEmpty(),
            kind = ProvenanceKind.entries.firstOrNull { it.name == kind.text() }
                ?: kind.fail("a kind is one of ${ProvenanceKind.entries}, not [${kind.text()}]"),
            publishedBy = actor(node.required("publishedBy")),
            approvedBy = node.field("approvedBy")?.elements()?.map(::actor).orEmpty(),
            shadow = node.field("shadow")?.let { shadow ->
                shadow.requireObject("window", "skipped")
                ShadowWindow(shadow.field("window")?.nonEmpty(), shadow.required("skipped").bool())
            },
        )
    }

    internal fun tree(provenance: Provenance): ObjectNode {
        val node = factory.objectNode()
        provenance.parent?.let { node.putObject("parent").put("revision", it.revision).put("digest", it.digest) }
        node.put("change", provenance.change)
        node.put("kind", provenance.kind.name)
        node.set("publishedBy", actor(provenance.publishedBy))
        if (provenance.approvedBy.isNotEmpty()) {
            node.putArray("approvedBy").also { approvers -> provenance.approvedBy.forEach { approvers.add(actor(it)) } }
        }
        provenance.shadow?.let { shadow ->
            val item = node.putObject("shadow")
            shadow.window?.let { item.put("window", it) }
            item.put("skipped", shadow.skipped)
        }
        return node
    }
}
