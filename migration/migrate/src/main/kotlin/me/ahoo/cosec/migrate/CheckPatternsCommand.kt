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
import me.ahoo.cosec.control.change.ChangeRequestCodec
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.control.store.ListedChange
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.migrate.MigrateMain.FAILED
import me.ahoo.cosec.migrate.MigrateMain.OK
import me.ahoo.cosec.migrate.RedisAccess.controlStore
import me.ahoo.cosec.model.Identifiers
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.io.PrintStream

/**
 * `check-patterns` (`DOC-15`): before an upgrade from 8.0.0-beta.8, the patterns of the stored heads and of the open
 * changes (their operations, so also the candidates of the staged and shadowing ones) that the new version reads
 * otherwise: one it does not compile, and one that holds a backslash, which it compiles with another meaning without an
 * error. Each with where it is, how to write it so that it keeps its meaning, and what stands in for it during the
 * upgrade: a wider pattern for a DENY or a rate limit, never for a grant, and never a DENY narrowed or removed. It reads the control plane's store and writes nothing. An open
 * change named here fails after the upgrade: its publish or rebase sends it back to `DRAFT` with a `COMPILE` finding,
 * and its shadow window closes blocked on the first upgraded instance.
 */
internal object CheckPatternsCommand {
    /** What one document's head, or one open [change] of it, holds: its findings, or why it could not be read. */
    class Checked(
        val key: SnapshotKey,
        val findings: List<PatternScan.Finding>,
        val failure: String? = null,
        val change: String? = null,
    ) {
        /** What the report names: the document, or the change of it. */
        val subject: String get() = change?.let { "$key change $it" } ?: key.toString()
    }

    fun checkPatterns(options: Options, out: PrintStream): Int {
        val only = options.value("document")?.let { text ->
            PublishCommand.documentKey(text)
                ?: throw UsageException("--document is platform, app:<id> or tenant:<id>, not [${Identifiers.printable(text)}].")
        }
        val prefix = options.value("prefix")
        val scrub = { text: String -> RedisUris.scrub(text, options.redisUris) }
        return controlStore(options) { store -> report(check(store, only, prefix), out, scrub) }
    }

    /**
     * The heads of [store] checked, each followed by its open changes in id order: [only] that document, or every
     * document whose key starts with [prefix], in key order.
     */
    fun check(store: ControlStore, only: SnapshotKey? = null, prefix: String? = null): List<Checked> {
        val keys = only?.let(::listOf) ?: runBlocking { store.documents() }
            .filter { prefix == null || it.toString().startsWith(prefix) }
            .sortedBy { it.toString() }
        return keys.flatMap { key ->
            val head = read(key, null) {
                runBlocking { store.head(key) }?.document?.bodyBytes()?.let(PatternScan::scan).orEmpty()
            }
            listOf(head) + changes(store, key)
        }
    }

    /** The open changes of [key], each checked; one entry naming the failure when they cannot be listed. */
    private fun changes(store: ControlStore, key: SnapshotKey): List<Checked> {
        val listed = attempt({ return listOf(failed(key, "*", it)) }) {
            runBlocking { store.openChanges(key) }.sortedBy { it.id.value }
        }
        return listed.map { change ->
            when (change) {
                is ListedChange.Readable -> read(key, change.id.value) {
                    PatternScan.scanOperations(ChangeRequestCodec.encode(change.change))
                }
                is ListedChange.Unreadable -> Checked(key, emptyList(), "its record does not read", change.id.value)
            }
        }
    }

    private inline fun read(key: SnapshotKey, change: String?, scan: () -> List<PatternScan.Finding>): Checked =
        attempt({ failed(key, change, it) }) { Checked(key, scan(), change = change) }

    /** [block], or [failure] of what it threw: one read's failure is its finding, and the next is still checked. */
    // stays: one document's or change's failure to read is its finding; cancellation propagates
    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> attempt(failure: (Exception) -> T, block: () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        failure(error)
    }

    private fun failed(key: SnapshotKey, change: String?, error: Exception) =
        Checked(key, emptyList(), error.message ?: error.javaClass.name, change)

    /** One line per document and per finding, then a summary; a finding, or a document not read, exits [FAILED]. */
    fun report(checked: List<Checked>, out: PrintStream, scrub: (String) -> String = { it }): Int {
        for (document in checked) {
            when {
                document.failure != null -> out.println("FAILED ${document.subject}: ${scrub(document.failure)}")
                document.findings.isEmpty() -> out.println("ok ${document.subject}")
                else -> document.findings.forEach { out.println(line(document.subject, it)) }
            }
        }
        val patterns = checked.sumOf { it.findings.size }
        val documents = checked.count { it.findings.isNotEmpty() }
        val failed = checked.count { it.failure != null }
        val changes = checked.count { it.change != null }
        out.println(
            "Checked ${checked.size - changes} document(s) and $changes open change(s): $patterns pattern(s) to " +
                "rewrite in $documents of them, $failed could not be read."
        )
        return if (patterns + failed > 0) FAILED else OK
    }

    private fun line(subject: String, finding: PatternScan.Finding): String {
        val head = "PATTERN $subject ${finding.location}: ${Identifiers.printable(finding.problem)}"
        val written = finding.written ?: return "$head; ${unwritten(finding)}"
        return "$head; write ${shown(written)}, once every instance runs this version; until then ${window(finding)}"
    }

    private fun shown(pattern: String) =
        "${Identifiers.printable(pattern)}, in JSON ${CoSecJson.mapper.writeValueAsString(pattern)}"

    /**
     * What keeps the entry's meaning while instances of both versions decide (`DOC-15`): never a DENY narrowed or
     * removed, never a wildcard that would grant more.
     */
    private fun window(finding: PatternScan.Finding): String {
        val bridge = finding.bridge
        return when {
            finding.effect == PatternScan.Effect.GRANT ->
                "narrow or remove this grant for the window: a wildcard both versions read alike would grant more"
            bridge != null -> "write ${shown(bridge)}, which both versions read alike and which matches more"
            finding.beside ->
                "keep this pattern and add the exact form beside it in the same entry: each version matches what was " +
                    "meant with one of the two, and the other matches a little more on this version, nothing meant on " +
                    "8.0.0-beta.8"
            else ->
                "keep it as it is: an instance of this version fails its document closed (503) on its unescaped " +
                    "reserved character until the exact form is published; never narrow or remove it for the window"
        }
    }

    /** A path pattern no escape writes: it matched no request path on the version that stored it. */
    private fun unwritten(finding: PatternScan.Finding): String = when {
        !finding.moved -> "no escape writes it: it matches no request path, so name the path as the pipeline sees it"
        finding.effect == PatternScan.Effect.GRANT ->
            "it granted nothing on 8.0.0-beta.8 and grants that path on this version: remove it before the upgrade, " +
                "or write the path you mean escaped once every instance runs this version"
        else ->
            "it matched nothing on 8.0.0-beta.8 and matches that path on this version, which only restricts more: keep " +
                "it, or remove it"
    }
}
