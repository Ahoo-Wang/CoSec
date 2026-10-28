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

package me.ahoo.cosec.runtime.snapshot

import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.model.condition.AttributeCatalog
import me.ahoo.cosec.model.condition.ConditionLimits
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.snapshot.SnapshotCompiler
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotFormatException
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.SnapshotSource
import me.ahoo.cosec.snapshot.document.PolicyDocument
import me.ahoo.cosec.snapshot.document.PolicyDocumentCodec
import me.ahoo.cosec.snapshot.document.PolicyDocumentCompiler

/**
 * The data plane's compiler (RFC 0007 §3.1 N3): every document compiles against the condition limits and external
 * attributes of the platform document this instance last compiled, as the control plane checks candidates
 * (`PolicyDocumentCompiler.forPlatform`), plus the attributes the registered providers declare ([providers]).
 *
 * When a platform revision changes its limits or attributes, the app and tenant documents of the bound source, installed
 * or loading, may have compiled against the previous ones, so they are read and compiled again ([bind],
 * [SnapshotSource.recompile]); one that cannot be compiled again fails closed. Until the platform is
 * compiled the defaults apply; a host loads the platform first so that no instance document compiles against them.
 */
class PlatformCompiler(
    private val providers: AttributeCatalog = AttributeCatalog.NONE,
    private val principalBindingLimit: Int = PolicyDocumentCompiler.DEFAULT_PRINCIPAL_BINDING_LIMIT,
) : SnapshotCompiler<CompiledDocument> {
    private class Inputs(val signature: List<Any>, val compiler: PolicyDocumentCompiler)

    @Volatile
    private var inputs: Inputs = inputsOf(null)

    @Volatile
    private var source: SnapshotSource<CompiledDocument, CompiledDocument>? = null

    /** Recompiles [source]'s app and tenant documents whenever the platform's limits or attributes change. */
    fun bind(source: SnapshotSource<CompiledDocument, CompiledDocument>) {
        this.source = source
    }

    /** The compiler documents compile with now: the last compiled platform's limits and attributes. */
    val current: PolicyDocumentCompiler get() = inputs.compiler

    /**
     * The bound source's entry point, and only its: compiling the platform makes its limits and attributes the live
     * inputs of this instance. To check a candidate without that, compile it with `PolicyDocumentCompiler.forPlatform`.
     */
    override fun compile(key: SnapshotKey, document: SnapshotDocument): CompiledDocument {
        if (key != SnapshotKey.Platform) {
            return inputs.compiler.compile(key, document)
        }
        if (document.schema != PolicyDocumentCodec.SCHEMA) {
            throw SnapshotFormatException(
                "[$key] has schema ${document.schema}, not this version's document format ${PolicyDocumentCodec.SCHEMA}.",
            )
        }
        val platform = PolicyDocumentCodec.decode(DocumentKind.PLATFORM, "", document.bodyBytes())
        val next = inputsOf(platform)
        // The platform itself compiles against its own limits and attributes: a platform that breaks them fails here.
        val compiled = next.compiler.compile(platform)
        val previous = inputs
        if (previous.signature != next.signature) {
            inputs = next
            recompileDependents()
        }
        return compiled
    }

    /**
     * After [inputs] changed: the documents installed and those whose load is in flight, which may have compiled with
     * the previous inputs and install after this call (review finding S4).
     */
    private fun recompileDependents() {
        source?.recompile()
    }

    private fun inputsOf(platform: PolicyDocument?): Inputs {
        val limits = platform?.limits ?: ConditionLimits.DEFAULT
        val declared = platform?.attributes.orEmpty().distinctBy { it.path }
        val byPath = declared.associateBy { it.path }
        val catalog = AttributeCatalog { path -> byPath[path] ?: providers.external(path) }
        val signature = listOf(
            limits.maxNodes,
            limits.maxDepth,
            limits.maxInValues,
            limits.maxCidrBlocks,
            limits.maxLiteralBytes,
            limits.maxCost,
            declared.map { "${it.path}:${it.type}" }.sorted(),
        )
        return Inputs(signature, PolicyDocumentCompiler.of(limits, catalog, principalBindingLimit))
    }
}
