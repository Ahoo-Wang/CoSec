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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.migrate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.migrate.MigrateMain.FAILED
import me.ahoo.cosec.migrate.MigrateMain.OK
import me.ahoo.cosec.migrate.RedisAccess.controlStore
import me.ahoo.cosec.model.Identifiers
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.io.PrintStream

/**
 * `check-patterns` (`DOC-15`): before an upgrade from 8.0.0-beta.8, the patterns of the stored heads that the new version
 * does not compile, each with where it is and how to write it. It reads the control plane's store and writes nothing.
 */
internal object CheckPatternsCommand {
    /** What one document's head holds: its findings, or why it could not be read. */
    class Checked(val key: SnapshotKey, val findings: List<PatternScan.Finding>, val failure: String? = null)

    fun checkPatterns(options: Options, out: PrintStream): Int {
        val only = options.value("document")?.let { text ->
            PublishCommand.documentKey(text)
                ?: throw UsageException("--document is platform, app:<id> or tenant:<id>, not [${Identifiers.printable(text)}].")
        }
        val prefix = options.value("prefix")
        val scrub = { text: String -> RedisUris.scrub(text, options.redisUris) }
        return controlStore(options) { store -> report(check(store, only, prefix), out, scrub) }
    }

    /** The heads of [store] checked: [only] that one, or every document whose key starts with [prefix], in key order. */
    // stays: one document's failure to read is its finding, and the next is still checked; cancellation propagates
    @Suppress("TooGenericExceptionCaught")
    fun check(store: ControlStore, only: SnapshotKey? = null, prefix: String? = null): List<Checked> {
        val keys = only?.let(::listOf) ?: runBlocking { store.documents() }
            .filter { prefix == null || it.toString().startsWith(prefix) }
            .sortedBy { it.toString() }
        return keys.map { key ->
            try {
                val body = runBlocking { store.head(key) }?.document?.bodyBytes()
                Checked(key, body?.let(PatternScan::scan).orEmpty())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Checked(key, emptyList(), error.message ?: error.javaClass.name)
            }
        }
    }

    /** One line per document and per finding, then a summary; a finding, or a document not read, exits [FAILED]. */
    fun report(checked: List<Checked>, out: PrintStream, scrub: (String) -> String = { it }): Int {
        for (document in checked) {
            when {
                document.failure != null -> out.println("FAILED ${document.key}: ${scrub(document.failure)}")
                document.findings.isEmpty() -> out.println("ok ${document.key}")
                else -> document.findings.forEach { out.println(line(document.key, it)) }
            }
        }
        val patterns = checked.sumOf { it.findings.size }
        val documents = checked.count { it.findings.isNotEmpty() }
        val failed = checked.count { it.failure != null }
        out.println(
            "Checked ${checked.size} document(s): $patterns pattern(s) to rewrite in $documents document(s), " +
                "$failed could not be read."
        )
        return if (patterns + failed > 0) FAILED else OK
    }

    private fun line(key: SnapshotKey, finding: PatternScan.Finding): String {
        val head = "PATTERN $key ${finding.location}: ${Identifiers.printable(finding.problem)}"
        val written = finding.written
            ?: return "$head; no escape writes it: it matches no request path, so name the path as the pipeline sees it"
        return "$head; write ${Identifiers.printable(written)}, in JSON ${CoSecJson.mapper.writeValueAsString(written)}"
    }
}
