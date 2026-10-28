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

package me.ahoo.cosec.runtime.shadow

import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotFormatException
import me.ahoo.cosec.snapshot.SnapshotKey
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.time.Instant

/**
 * Candidates, shadow reports and instance statuses as JSON (RFC 0007 §6.4, §6.5), for every store of them. Reading is
 * strict: anything else is a [SnapshotFormatException], never a value read as absent.
 */
object ShadowCodec {
    private val factory = JsonNodeFactory.instance
    private val reader = CoSecJsonSerializer.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)

    fun encode(candidate: StoredCandidate): ByteArray = write(
        factory.objectNode().put("window", candidate.window).put("baseRevision", candidate.baseRevision)
            .put("schema", candidate.document.schema).put("revision", candidate.document.revision)
            .put("publishedAt", candidate.document.publishedAt.toString())
            .set("document", read(candidate.document.bodyBytes())),
    )

    fun decodeCandidate(bytes: ByteArray): StoredCandidate = guarded {
        val node = read(bytes)
        node.only("window", "baseRevision", "schema", "revision", "publishedAt", "document")
        StoredCandidate(
            node.need("window").text(),
            node.need("baseRevision").long(),
            SnapshotDocument(
                node.need("schema").asInt(),
                node.need("revision").long(),
                Instant.parse(node.need("publishedAt").text()),
                CoSecJsonSerializer.writeValueAsBytes(node.need("document")),
            ),
        )
    }

    /** One instance's report of one window: each is kept apart, so that no instance writes another's (review R1). */
    fun encodeReport(report: ShadowReport): ByteArray = write(report(report))

    fun decodeReport(bytes: ByteArray): ShadowReport = guarded { report(read(bytes)) }

    /** Every instance's last status, by instance. */
    fun encodeStatuses(statuses: Map<String, InstanceStatus>): ByteArray = write(
        factory.objectNode().apply {
            statuses.toSortedMap().forEach { (instance, status) ->
                val node = putObject(instance).put("at", status.at.toString())
                val documents = node.putObject("documents")
                status.documents.forEach { (key, installed) ->
                    documents.putObject(
                        key.toString()
                    ).put("revision", installed.revision).put("lagMillis", installed.lag.toMillis())
                }
            }
        },
    )

    fun decodeStatuses(bytes: ByteArray): Map<String, InstanceStatus> = guarded {
        read(bytes).properties().associate { (instance, node) ->
            node.only("at", "documents")
            instance to InstanceStatus(
                instance,
                Instant.parse(node.need("at").text()),
                node.need("documents").properties().associate { (key, document) ->
                    document.only("revision", "lagMillis")
                    documentKey(key) to InstalledDocument(document.need("revision").long(), Duration.ofMillis(document.need("lagMillis").long()))
                },
            )
        }
    }

    private fun report(report: ShadowReport): ObjectNode = factory.objectNode().apply {
        put("window", report.window)
        put("instance", report.instance)
        putObject(
            "counts"
        ).apply { report.counts.toSortedMap().forEach { (outcome, count) -> put(outcome.name, count) } }
        putObject("samples").apply {
            report.samples.toSortedMap().forEach { (outcome, samples) ->
                putArray(outcome.name).apply {
                    samples.forEach { sample ->
                        addObject().put(
                            "tenant",
                            sample.tenant
                        ).put("principal", sample.principal).put("app", sample.app)
                            .put("space", sample.space).put("action", sample.action).put("resource", sample.resource)
                            .put("enforcedBy", sample.enforcedBy).put("candidateBy", sample.candidateBy)
                    }
                }
            }
        }
        putArray("baseRevisions").apply { report.baseRevisions.sorted().forEach { add(it) } }
        report.first?.let { put("first", it.toString()) }
        report.last?.let { put("last", it.toString()) }
    }

    private fun report(node: JsonNode): ShadowReport {
        node.only("window", "instance", "counts", "samples", "baseRevisions", "first", "last")
        return ShadowReport(
            window = node.need("window").text(),
            instance = node.need("instance").text(),
            counts = node.need(
                "counts"
            ).properties().associate { (outcome, count) -> ShadowOutcome.valueOf(outcome) to count.long() },
            samples = node.need("samples").properties().associate { (outcome, samples) ->
                ShadowOutcome.valueOf(outcome) to samples.values().map { sample ->
                    sample.only("tenant", "principal", "app", "space", "action", "resource", "enforcedBy", "candidateBy")
                    ShadowSample(
                        sample.need("tenant").text(),
                        sample.need("principal").text(),
                        sample.need("app").text(),
                        sample.need("space").text(),
                        sample.need("action").text(),
                        sample.need("resource").text(),
                        sample.get("enforcedBy")?.takeUnless { it.isNull }?.text(),
                        sample.get("candidateBy")?.takeUnless { it.isNull }?.text(),
                    )
                }
            },
            baseRevisions = node.need("baseRevisions").values().map { it.long() }.toSet(),
            first = node.get("first")?.let { Instant.parse(it.text()) },
            last = node.get("last")?.let { Instant.parse(it.text()) },
        )
    }

    private fun documentKey(text: String): SnapshotKey = when {
        text == "platform" -> SnapshotKey.Platform
        text.startsWith("app:") -> SnapshotKey.App(text.removePrefix("app:"))
        text.startsWith("tenant:") -> SnapshotKey.Tenant(text.removePrefix("tenant:"))
        else -> malformed("[$text] is not a document key")
    }

    private fun write(node: JsonNode): ByteArray = CoSecJsonSerializer.writeValueAsBytes(node)

    private fun read(bytes: ByteArray): JsonNode = try {
        reader.readTree(bytes) ?: malformed("empty")
    } catch (error: JacksonException) {
        throw SnapshotFormatException("A shadow value is not JSON: ${error.originalMessage}", error)
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (error: SnapshotFormatException) {
        throw error
    } catch (error: JacksonException) {
        throw SnapshotFormatException("A shadow value is malformed: ${error.originalMessage}", error)
    } catch (error: IllegalArgumentException) {
        throw SnapshotFormatException("A shadow value is malformed: ${error.message}", error)
    } catch (error: java.time.format.DateTimeParseException) {
        throw SnapshotFormatException("A shadow value is malformed: ${error.message}", error)
    }

    private fun JsonNode.need(name: String): JsonNode = get(name) ?: malformed("[$name] is missing")

    private fun JsonNode.text(): String = if (isString) asString() else malformed("$this is not a string")

    private fun JsonNode.long(): Long = if (isIntegralNumber) asLong() else malformed("$this is not an integer")

    private fun JsonNode.only(vararg names: String) {
        if (!isObject) malformed("$this is not an object")
        val unknown = properties().map { it.key }.toSet() - names.toSet()
        if (unknown.isNotEmpty()) malformed("unknown fields $unknown")
    }

    private fun malformed(reason: String): Nothing = throw SnapshotFormatException(
        "A shadow value is malformed: $reason."
    )
}
