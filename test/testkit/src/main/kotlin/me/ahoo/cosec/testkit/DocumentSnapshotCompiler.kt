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

package me.ahoo.cosec.testkit

import me.ahoo.cosec.CoSecException
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.model.condition.AttributeCatalog
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.snapshot.document.PolicyDocument
import me.ahoo.cosec.snapshot.document.PolicyDocumentCodec
import me.ahoo.cosec.snapshot.document.PolicyDocumentCompiler
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

/** The documents of a scenario, as written. */
class ScenarioDocuments(val platform: JsonNode?, val apps: Map<String, JsonNode>, val tenants: Map<String, JsonNode>)

/** Every document of a scenario, compiled. */
class CompiledSnapshot(
    val platform: CompiledDocument,
    val apps: Map<String, CompiledDocument>,
    val tenants: Map<String, CompiledDocument>,
)

/** Turns scenario documents into what the engine evaluates. */
fun interface SnapshotCompiler {
    /** Throws [DocumentException] when a document does not compile. */
    fun compile(documents: ScenarioDocuments): CompiledSnapshot
}

/**
 * Compiles scenario documents the way a stored snapshot is compiled: [PolicyDocumentCodec] reads them and
 * [PolicyDocumentCompiler] compiles them (RFC 0004 §7.1). A scenario writes a document's body without the header,
 * which this adds.
 *
 * One addition for the conformance suite only: `"fault": "message"` on a statement or a policy makes evaluating that statement's
 * condition, or that policy's condition, throw. It stands for any failing input (a contract violation, an external
 * attribute that cannot be fetched) so the combination rules can be checked without depending on how a failure
 * arises. It becomes a condition that reads the host attribute `request.attributes.<FAULT><message>` first, which
 * [RequestFacts.host] answers by throwing [ScenarioFault]; the codec itself has no such field. [stored] leaves it out,
 * so a user's documents compile exactly as they do when published.
 */
object DocumentSnapshotCompiler : SnapshotCompiler {
    private val factory = JsonNodeFactory.instance

    override fun compile(documents: ScenarioDocuments): CompiledSnapshot =
        compile(documents, AttributeCatalog.NONE, faults = true)

    /** Compiles as this does, with the external attributes of [catalog] declared (RFC 0006 §5.4.1). */
    fun with(catalog: AttributeCatalog): SnapshotCompiler = SnapshotCompiler { compile(it, catalog, faults = true) }

    /** Compiles as a published document is compiled: without `"fault"`. */
    val stored: SnapshotCompiler = SnapshotCompiler { compile(it, AttributeCatalog.NONE, faults = false) }

    /**
     * As an instance compiles them ([PolicyDocumentCompiler.forPlatform], what the runtime's `PlatformCompiler`
     * uses): every document, the platform's own included, against the platform's `limits` and the attributes it
     * declares, plus those of [providers].
     */
    private fun compile(documents: ScenarioDocuments, providers: AttributeCatalog, faults: Boolean): CompiledSnapshot {
        val platform = decode(DocumentKind.PLATFORM, "", documents.platform, faults)
        val compiler = guarded("platform") { PolicyDocumentCompiler.forPlatform(platform, providers) }
        return CompiledSnapshot(
            platform = guarded("platform") { compiler.compile(platform) },
            apps = documents.apps.mapValues { (app, node) -> compile(compiler, DocumentKind.APP, app, node, faults) },
            tenants = documents.tenants.mapValues { (tenant, node) ->
                compile(compiler, DocumentKind.TENANT, tenant, node, faults)
            },
        )
    }

    private fun compile(
        compiler: PolicyDocumentCompiler,
        kind: DocumentKind,
        owner: String,
        node: JsonNode?,
        faults: Boolean,
    ): CompiledDocument {
        val document = decode(kind, owner, node, faults)
        return guarded(name(kind, owner)) { compiler.compile(document) }
    }

    private fun decode(kind: DocumentKind, owner: String, node: JsonNode?, faults: Boolean): PolicyDocument {
        val name = name(kind, owner)
        val body = (node?.deepCopy() ?: factory.objectNode()) as? ObjectNode
            ?: throw ScenarioFormatException("The $name document is not an object: $node")
        val document = factory.objectNode()
            .put("schema", PolicyDocumentCodec.SCHEMA)
            .put("revision", 1)
            .put("publishedAt", PUBLISHED_AT)
        document.setAll(body)
        if (faults) {
            document.get("policies")?.values()?.forEach { faulting(it as ObjectNode) }
        }
        return guarded(
            name
        ) { PolicyDocumentCodec.decode(kind, owner, CoSecJsonSerializer.writeValueAsBytes(document)) }
    }

    private fun name(kind: DocumentKind, owner: String): String =
        if (kind == DocumentKind.PLATFORM) "platform" else "${kind.name.lowercase()} $owner"

    /** SnapshotFormatException (does not decode) or DocumentCompileException (does not compile), as the outcome. */
    private fun <T> guarded(name: String, block: () -> T): T = try {
        block()
    } catch (error: CoSecException) {
        throw DocumentException("The $name document: ${error.message}", error)
    }

    /** Moves the `fault`s of a policy and its statements into their conditions. */
    private fun faulting(policy: ObjectNode) {
        fault(policy)
        policy.get("statements")?.values()?.forEach { (it as? ObjectNode)?.let(::fault) }
    }

    private fun fault(owner: ObjectNode) {
        val fault = owner.remove(FAULT_FIELD) ?: return
        val probe = factory.objectNode()
        probe.putArray("eq").add("request.attributes.$FAULT${fault.asString()}").add("")
        val condition = owner.remove(CONDITION)
        owner.set(
            CONDITION,
            if (condition == null) probe else factory.objectNode().also { it.putArray("and").add(probe).add(condition) }
        )
    }

    private const val FAULT_FIELD = "fault"
    private const val CONDITION = "condition"
    private const val PUBLISHED_AT = "2026-10-26T00:00:00Z"
}

/** A failure injected by `"fault"`. */
class ScenarioFault(message: String) : RuntimeException(message)

/** The prefix of the request attribute a `"fault"` reads. */
internal const val FAULT: String = "cosec.fault:"
