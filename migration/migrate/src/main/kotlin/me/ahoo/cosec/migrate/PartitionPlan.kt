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

package me.ahoo.cosec.migrate

import me.ahoo.cosec.control.change.EntryDigest
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.runtime.document.PolicyDocumentCodec
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * `partition plan` (RFC 0007 §9.4, step 1): the changes that copy each tenant's attachable policies from the platform
 * document, as `convert --partition platform` placed them, into that tenant's own document, **exactly**, so that both
 * layers hold the same content and resolve it alike (N2): the shadow window should show no difference. The owners are
 * what `convert --partition by-owner` finds. Each tenant's plan is the desired document `apply` merges; nothing is
 * written to Redis. Freezing, removing the platform copies and unfreezing (steps 2 to 4) are ordinary changes after it.
 */
object PartitionPlan {
    /** For each tenant, its owned attachable policies as the platform document holds them. */
    fun of(platform: Conversion, byOwner: Conversion): Map<SnapshotKey, PolicyDocument> {
        val shared = platform.documents[SnapshotKey.Platform]?.policies.orEmpty().associateBy { it.id }
        return byOwner.documents.filterKeys { it is SnapshotKey.Tenant }.mapNotNull { (key, document) ->
            val copies = document.policies.filter { it.attachable }.mapNotNull { owned ->
                shared[owned.id]?.takeIf { EntryDigest.of(it) == EntryDigest.of(owned) }
            }
            if (copies.isEmpty()) {
                null
            } else {
                key to PolicyDocument(DocumentKind.TENANT, (key as SnapshotKey.Tenant).tenantId, 1, Instant.EPOCH, copies, emptyList())
            }
        }.toMap()
    }

    /** Writes each tenant's plan to `tenant-<id>.json` in [directory], to be submitted with `apply` (merge). */
    fun write(plan: Map<SnapshotKey, PolicyDocument>, directory: Path): List<Path> {
        Files.createDirectories(directory)
        return plan.map { (key, document) ->
            val file = directory.resolve("tenant-${(key as SnapshotKey.Tenant).tenantId}.json")
            Files.write(file, PolicyDocumentCodec.encode(document))
            file
        }
    }
}
