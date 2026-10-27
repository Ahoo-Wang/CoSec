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

import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.document.PolicyDocument
import me.ahoo.cosec.snapshot.document.PolicyDocumentCodec
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * The output directory of `convert` and the input of `publish` (RFC 0004 §8.2):
 *
 * - `platform.json`, `apps/<appId>.json`, `tenants/<tenantId>.json`: the documents, as stored in Redis (ids in file
 *   names are URL-encoded, so no id can name a path outside the directory);
 * - `provenance.json`: where each policy statement and binding came from in 7.x;
 * - `report.json`: rejections, warnings, semantic changes, counts and the SHA-256 of the input and of each file.
 *
 * A rejected conversion writes `report.json` only.
 */
object ConvertedDirectory {
    const val PLATFORM_FILE = "platform.json"
    const val APPS_DIRECTORY = "apps"
    const val TENANTS_DIRECTORY = "tenants"
    const val PROVENANCE_FILE = "provenance.json"
    const val REPORT_FILE = "report.json"

    private const val JSON = ".json"

    private val factory = JsonNodeFactory.instance
    private val pretty = CoSecJsonSerializer.writerWithDefaultPrettyPrinter()

    /** Writes [conversion] into [directory], which must not exist or be empty: `convert` never overwrites. */
    fun write(conversion: Conversion, directory: Path) {
        require(!Files.exists(directory) || (directory.isDirectory() && directory.listDirectoryEntries().isEmpty())) {
            "$directory is not empty: convert writes into a new directory."
        }
        Files.createDirectories(directory)
        val outputs = sortedMapOf<String, String>()
        for ((key, document) in conversion.documents) {
            val file = fileOf(key)
            val path = directory.resolve(file)
            Files.createDirectories(path.parent)
            val bytes = PolicyDocumentCodec.encode(document)
            Files.write(path, bytes, StandardOpenOption.CREATE_NEW)
            outputs[file] = sha256(bytes)
        }
        if (conversion.report.accepted) {
            val bytes = pretty.writeValueAsBytes(provenance(conversion.provenance))
            Files.write(directory.resolve(PROVENANCE_FILE), bytes, StandardOpenOption.CREATE_NEW)
            outputs[PROVENANCE_FILE] = sha256(bytes)
        }
        Files.write(
            directory.resolve(REPORT_FILE),
            pretty.writeValueAsBytes(report(conversion.report, outputs)),
            StandardOpenOption.CREATE_NEW,
        )
    }

    /**
     * The documents in [directory], decoded strictly: exactly the files the accepted conversion's `report.json`
     * lists, each with the SHA-256 recorded there. A file that changed, went missing or appeared since `convert`
     * (an edit, or two ids that collided on a case-insensitive file system) fails the read, so `publish` writes
     * only what `convert` produced.
     */
    fun read(directory: Path): Map<SnapshotKey, PolicyDocument> {
        val reportFile = directory.resolve(REPORT_FILE)
        require(Files.isRegularFile(reportFile)) { "$reportFile is missing: publish reads the output of convert." }
        val report = CoSecJsonSerializer.readTree(Files.readAllBytes(reportFile))
        require(report.get("accepted")?.asBoolean() == true) {
            "The conversion in $directory was rejected (see $REPORT_FILE): there is nothing to publish."
        }
        val recorded = report.get("outputSha256")?.properties()?.associate { it.key to it.value.asString() }.orEmpty()
            .filterKeys(::isDocumentFile)
        require(PLATFORM_FILE in recorded) { "$REPORT_FILE lists no $PLATFORM_FILE." }
        val present = documentFiles(directory)
        require(present == recorded.keys) {
            "The documents in $directory are not the ones convert wrote: missing ${recorded.keys - present}, " +
                "unexpected ${present - recorded.keys}."
        }
        val documents = LinkedHashMap<SnapshotKey, PolicyDocument>()
        for ((file, sha) in recorded.toSortedMap(compareBy<String> { it != PLATFORM_FILE }.thenBy { it })) {
            val bytes = Files.readAllBytes(directory.resolve(file))
            require(
                sha256(bytes) == sha
            ) { "$file changed since convert: its SHA-256 is not the one $REPORT_FILE records." }
            val (key, kind, owner) = keyOf(file)
            documents[key] = PolicyDocumentCodec.decode(kind, owner, bytes)
        }
        return documents
    }

    private fun isDocumentFile(file: String): Boolean =
        file == PLATFORM_FILE || file.startsWith("$APPS_DIRECTORY/") || file.startsWith("$TENANTS_DIRECTORY/")

    /** The document files in [directory], as relative paths. */
    private fun documentFiles(directory: Path): Set<String> {
        val files = HashSet<String>()
        if (Files.isRegularFile(directory.resolve(PLATFORM_FILE))) {
            files += PLATFORM_FILE
        }
        for (subdirectory in listOf(APPS_DIRECTORY, TENANTS_DIRECTORY)) {
            val path = directory.resolve(subdirectory)
            if (path.isDirectory()) {
                path.listDirectoryEntries().forEach { files += "$subdirectory/${it.name}" }
            }
        }
        return files
    }

    private fun keyOf(file: String): Triple<SnapshotKey, DocumentKind, String> {
        if (file == PLATFORM_FILE) {
            return Triple(SnapshotKey.Platform, DocumentKind.PLATFORM, "")
        }
        val owner = URLDecoder.decode(file.substringAfter('/').removeSuffix(JSON), Charsets.UTF_8)
        return if (file.startsWith("$APPS_DIRECTORY/")) {
            Triple(SnapshotKey.App(owner), DocumentKind.APP, owner)
        } else {
            Triple(SnapshotKey.Tenant(owner), DocumentKind.TENANT, owner)
        }
    }

    fun fileOf(key: SnapshotKey): String = when (key) {
        is SnapshotKey.App -> "$APPS_DIRECTORY/${encode(key.appId)}$JSON"
        is SnapshotKey.Tenant -> "$TENANTS_DIRECTORY/${encode(key.tenantId)}$JSON"
        else -> PLATFORM_FILE
    }

    private fun encode(id: String): String = URLEncoder.encode(id, Charsets.UTF_8)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun report(report: ConversionReport, outputs: Map<String, String> = emptyMap()): ObjectNode {
        val root = factory.objectNode()
        root.put("accepted", report.accepted)
        root.put("inputSha256", report.inputSha256)
        root.putObject("counts").also { counts -> report.counts.forEach { (name, count) -> counts.put(name, count) } }
        root.putArray("rejections").also { array ->
            report.rejections.forEach {
                array.addObject().put("kind", it.kind.name).put("location", it.location).put("message", it.message)
                    .put("advice", it.advice)
            }
        }
        root.putArray("warnings").also { array ->
            report.warnings.forEach { array.addObject().put("location", it.location).put("message", it.message) }
        }
        root.putArray("semanticChanges").also { array ->
            report.semanticChanges.forEach {
                array.addObject().put("change", it.change).put("location", it.location).put("shape", it.shape)
            }
        }
        root.putObject("outputSha256").also { node -> outputs.forEach { (file, sha) -> node.put(file, sha) } }
        return root
    }

    private fun provenance(provenance: Provenance): ObjectNode {
        val root = factory.objectNode()
        root.putArray("policies").also { array ->
            provenance.policies.forEach { origin ->
                array.addObject().put("document", origin.document).put("policyId", origin.policyId)
                    .put("source", origin.source)
                    .also { node ->
                        node.putArray(
                            "statements"
                        ).also { statements -> origin.statements.forEach(statements::add) }
                    }
            }
        }
        root.putArray("bindings").also { array ->
            provenance.bindings.forEach { origin ->
                array.addObject().put(
                    "document",
                    origin.document
                ).put("bindingId", origin.bindingId).put("source", origin.source)
            }
        }
        return root
    }
}
