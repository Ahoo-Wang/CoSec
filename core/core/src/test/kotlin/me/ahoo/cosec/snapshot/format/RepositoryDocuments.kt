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

package me.ahoo.cosec.snapshot.format

import me.ahoo.cosec.serialization.CoSecJsonSerializer
import tools.jackson.databind.JsonNode
import java.io.File

data class RepositoryDocument(val where: String, val file: SchemaFile, val node: JsonNode)

/**
 * Every document in the repository that has a format: the JSON files under `src/<set>/resources` of every module, and
 * the `json` code blocks of the documentation and skills that parse and name a known format.
 *
 * A resource file that is neither a known format nor [NOT_A_DOCUMENT_FORMAT] fails the search, so a new kind of file
 * is classified here before it lands. 7.x's formats (policies, app permissions, rate limit rules) have no descriptor:
 * only `cosec-migrate` reads them, with 7.x's own decoders, so their files and examples are skipped.
 */
object RepositoryDocuments {
    /** Resource files whose format has no schema: replay settings. */
    private val NOT_A_DOCUMENT_FORMAT = listOf(Regex("replay\\.json"))
    private val SKIPPED_DIRECTORIES = setOf("node_modules", "build", ".git", ".gradle", ".vitepress", ".idea", "docs")
    private val JSON_BLOCK = Regex("```json\\s*\\n(.*?)```", RegexOption.DOT_MATCHES_ALL)

    fun find(root: File): List<RepositoryDocument> {
        val files = root.walkTopDown().onEnter { it.name !in SKIPPED_DIRECTORIES }.filter { it.isFile }.toList()
        val resources = files.filter {
            it.extension == "json" && it.invariantSeparatorsPath.contains(Regex("/src/[^/]+/resources/"))
        }
            .flatMap { resource(root, it) }
        val blocks = files.filter { it.extension == "md" && isDocumentation(root, it) }.flatMap { markdown(root, it) }
        return resources + blocks
    }

    private fun isDocumentation(root: File, file: File): Boolean {
        val path = file.relativeTo(root).invariantSeparatorsPath
        return path.startsWith("wiki/") || path.startsWith("skills/") || !path.contains('/')
    }

    private fun resource(root: File, file: File): List<RepositoryDocument> {
        val where = file.relativeTo(root).invariantSeparatorsPath
        // The conformance suite's registry and scenarios (RFC 0008 §3.2, §3.3) are test data with a format of their own.
        if (NOT_A_DOCUMENT_FORMAT.any { it.matches(file.name) } || where.startsWith("test/conformance/")) {
            return emptyList()
        }
        return when {
            isLegacy(where, file) -> emptyList()
            // The test kit's examples: scenarios in the conformance format, and the documents they run against.
            where.startsWith("test/testkit/") && "/scenarios/" in where -> emptyList()
            where.startsWith("test/testkit/") && "/documents/" in where ->
                listOf(
                    RepositoryDocument(
                        where,
                        DocumentFormats.snapshotDocumentSchema,
                        CoSecJsonSerializer.readTree(file)
                    )
                )
            else -> throw IllegalStateException(
                "Resource [$where] has no known document format: classify it in RepositoryDocuments."
            )
        }
    }

    /** A 7.x policy, app permission or rate limit rules file. */
    private fun isLegacy(where: String, file: File): Boolean = where.contains("/cosec-rate-limit/") ||
        file.name.endsWith("-app-permission.json") ||
        file.name.endsWith("-policy.json") ||
        where.contains("/cosec-policy/")

    private fun markdown(root: File, file: File): List<RepositoryDocument> {
        val where = file.relativeTo(root).invariantSeparatorsPath
        return JSON_BLOCK.findAll(file.readText()).mapIndexedNotNull { index, block ->
            val node = runCatching {
                CoSecJsonSerializer.readTree(block.groupValues[1])
            }.getOrNull() ?: return@mapIndexedNotNull null
            formatOf(node)?.let { RepositoryDocument("$where json block ${index + 1}", it, node) }
        }.toList()
    }

    /** The format a documentation example shows, judged by its fields; null for fragments and other JSON. */
    private fun formatOf(node: JsonNode): SchemaFile? {
        val sample = if (node.isArray) node.firstOrNull() ?: return null else node
        if (!sample.isObject) {
            return null
        }
        return when {
            node.isArray -> null
            sample.has("policies") || sample.has("bindings") -> DocumentFormats.snapshotDocumentSchema
            else -> null
        }
    }
}
