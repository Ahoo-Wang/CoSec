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

package me.ahoo.cosec.migrate

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.runtime.document.PolicyDocument
import me.ahoo.cosec.runtime.document.PolicyDocumentCodec
import me.ahoo.cosec.runtime.json.CoSecJson
import me.ahoo.cosec.spi.snapshot.SnapshotKey
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
 *   names are URL-encoded, so no id can name a path outside the directory; an id whose encoded name would be longer
 *   than [MAX_FILE_NAME] gets a bounded name, a prefix of the encoded id, `~` and the SHA-256 of the id, and
 *   `report.json` records which id each such file holds in `documentIds`);
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
    private const val BOUNDED_SEPARATOR = "~"

    /** The longest file name a document gets. */
    const val MAX_FILE_NAME = 200

    private val factory = JsonNodeFactory.instance
    private val pretty = CoSecJson.mapper.writerWithDefaultPrettyPrinter()

    /**
     * Writes [conversion] into [directory], which must not exist or be empty: `convert` never overwrites. Every file name
     * is settled before the first file is written, and a write that fails removes what this call wrote, so a failed run
     * leaves the directory as it found it and can be run again.
     */
    fun write(conversion: Conversion, directory: Path) {
        require(!Files.exists(directory) || (directory.isDirectory() && directory.listDirectoryEntries().isEmpty())) {
            "$directory is not empty: convert writes into a new directory."
        }
        val files = conversion.documents.keys.associateWith { fileOf(it) }
        val folded = HashMap<String, SnapshotKey>()
        files.forEach { (key, file) ->
            val other = folded.putIfAbsent(file.lowercase(), key)
            require(other == null) { "$other and $key would be written to files that differ only in case: $file." }
        }
        val created = ArrayList<Path>()
        var complete = false
        try {
            createDirectories(directory, created)
            val outputs = sortedMapOf<String, String>()
            val documentIds = sortedMapOf<String, String>()
            for ((key, document) in conversion.documents) {
                val file = files.getValue(key)
                val path = directory.resolve(file)
                createDirectories(path.parent, created)
                val bytes = PolicyDocumentCodec.encode(document)
                Files.write(path, bytes, StandardOpenOption.CREATE_NEW)
                created.add(path)
                outputs[file] = sha256(bytes)
                if (isBounded(file)) documentIds[file] = ownerOf(key)
            }
            if (conversion.report.accepted) {
                val bytes = pretty.writeValueAsBytes(provenance(conversion.provenance))
                val path = directory.resolve(PROVENANCE_FILE)
                Files.write(path, bytes, StandardOpenOption.CREATE_NEW)
                created.add(path)
                outputs[PROVENANCE_FILE] = sha256(bytes)
            }
            val report = directory.resolve(REPORT_FILE)
            Files.write(
                report,
                pretty.writeValueAsBytes(report(conversion.report, outputs, documentIds)),
                StandardOpenOption.CREATE_NEW
            )
            created.add(report)
            complete = true
        } finally {
            if (!complete) {
                // Only what this call created: the directory was new or empty when it started.
                created.asReversed().forEach(Files::deleteIfExists)
            }
        }
    }

    /** Creates [directory] and the parents that are missing, recording each one created in [created]. */
    private fun createDirectories(directory: Path, created: MutableList<Path>) {
        val missing = generateSequence(directory) { it.parent }.takeWhile { !Files.exists(it) }.toList()
        Files.createDirectories(directory)
        created.addAll(missing.asReversed())
    }

    private fun ownerOf(key: SnapshotKey): String = when (key) {
        is SnapshotKey.App -> key.appId
        is SnapshotKey.Tenant -> key.tenantId
        else -> ""
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
        val report = CoSecJson.mapper.readTree(Files.readAllBytes(reportFile))
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
        val recordedIds = report.get("documentIds")?.properties()?.associate { it.key to it.value.asString() }.orEmpty()
        val documents = LinkedHashMap<SnapshotKey, PolicyDocument>()
        for ((file, sha) in recorded.toSortedMap(compareBy<String> { it != PLATFORM_FILE }.thenBy { it })) {
            val bytes = Files.readAllBytes(directory.resolve(file))
            require(
                sha256(bytes) == sha
            ) { "$file changed since convert: its SHA-256 is not the one $REPORT_FILE records." }
            val (key, kind, owner) = keyOf(file, recordedIds)
            // The name must be the one convert gives this id: a bounded name carries the SHA-256 of the id it holds.
            require(
                fileOf(key) == file
            ) { "$file is not the file name of $key: $REPORT_FILE does not describe this directory." }
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

    private fun keyOf(file: String, recordedIds: Map<String, String>): Triple<SnapshotKey, DocumentKind, String> {
        if (file == PLATFORM_FILE) {
            return Triple(SnapshotKey.Platform, DocumentKind.PLATFORM, "")
        }
        val name = file.substringAfter('/').removeSuffix(JSON)
        val owner = if (isBounded(file)) {
            requireNotNull(recordedIds[file]) { "$REPORT_FILE does not record the id $file holds." }
        } else {
            URLDecoder.decode(name, Charsets.UTF_8)
        }
        return if (file.startsWith("$APPS_DIRECTORY/")) {
            Triple(SnapshotKey.App(owner), DocumentKind.APP, owner)
        } else {
            Triple(SnapshotKey.Tenant(owner), DocumentKind.TENANT, owner)
        }
    }

    /** The file of [key]'s document, relative to the directory. */
    fun fileOf(key: SnapshotKey): String = when (key) {
        is SnapshotKey.App -> "$APPS_DIRECTORY/${fileName(key.appId)}"
        is SnapshotKey.Tenant -> "$TENANTS_DIRECTORY/${fileName(key.tenantId)}"
        else -> PLATFORM_FILE
    }

    /**
     * The name of the file that holds the document of [id]: the URL-encoded id and `.json`. An id whose encoded name is
     * longer than [MAX_FILE_NAME] (a legal id of 30 Chinese characters is 275 bytes encoded, more than file systems
     * take, #1256) is cut at a `%XX` boundary and followed by `~`, which the encoding never produces, and the SHA-256
     * of the whole id, so two ids never share a name and the name can be checked against the id it is said to hold.
     */
    fun fileName(id: String): String {
        val encoded = URLEncoder.encode(id, Charsets.UTF_8)
        if (encoded.length + JSON.length <= MAX_FILE_NAME) {
            return encoded + JSON
        }
        val hash = sha256(id.encodeToByteArray())
        var end = MAX_FILE_NAME - JSON.length - BOUNDED_SEPARATOR.length - hash.length
        if (encoded[end - 1] == '%') {
            end -= 1
        } else if (encoded[end - 2] == '%') {
            end -= 2
        }
        return encoded.substring(0, end) + BOUNDED_SEPARATOR + hash + JSON
    }

    private fun isBounded(file: String): Boolean = BOUNDED_SEPARATOR in file

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun report(
        report: ConversionReport,
        outputs: Map<String, String> = emptyMap(),
        documentIds: Map<String, String> = emptyMap(),
    ): ObjectNode {
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
        root.putObject("documentIds").also { node -> documentIds.forEach { (file, id) -> node.put(file, id) } }
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
