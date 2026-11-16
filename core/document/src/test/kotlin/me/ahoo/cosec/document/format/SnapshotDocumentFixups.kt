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

package me.ahoo.cosec.document.format

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * What a generated snapshot document needs to be one the codec can read, which the descriptors cannot say: one schema
 * describes the three kinds of document, so it accepts combinations that no single kind reads.
 *
 * Ids are made unique where the codec rejects duplicates. An app document's principals must name their tenant, which the
 * shared binding schema cannot require of one kind of document only ([FormatPatterns]), so they get one when the
 * document can only be an app's: it has a `catalog`, or a role that lists `"*"` (a tenant's role may not, and a platform
 * document has no roles). Shared by the tests that decode generated documents.
 */
internal fun selfConsistentSnapshotDocument(document: JsonNode): JsonNode {
    val onlyAnAppDocument = document.has("catalog") || document.get("roles")?.values()?.any(::listsEverything) == true
    if (onlyAnAppDocument) {
        document.get("bindings")?.values()?.map { it.get("subject") }?.filterIsInstance<ObjectNode>()
            ?.filter { it.has("principal") && !it.has("tenant") }?.forEach { it.put("tenant", "acme") }
    }
    document.get("policies")?.values()?.toList().orEmpty().forEachIndexed { index, entry ->
        (entry as ObjectNode).put("id", "${entry.get("id").asString()}-$index")
    }
    return document
}

private fun listsEverything(role: JsonNode): Boolean =
    role.get("permissions")?.let { it.isString && it.asString() == "*" } == true
