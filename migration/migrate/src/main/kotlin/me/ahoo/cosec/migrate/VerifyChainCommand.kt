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

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.store.ChainCheck
import me.ahoo.cosec.control.store.IndexCheck
import me.ahoo.cosec.migrate.MigrateMain.FAILED
import me.ahoo.cosec.migrate.MigrateMain.OK
import me.ahoo.cosec.migrate.RedisAccess.controlStore
import me.ahoo.cosec.model.Identifiers
import java.io.PrintStream

/** `verify-chain`. */
internal object VerifyChainCommand {
    /** `verify-chain`: one line per document; a broken chain, or one that cannot be read, exits with [FAILED]. */
    fun verifyChain(options: Options, out: PrintStream): Int {
        val only = options.value("document")?.let { text ->
            PublishCommand.documentKey(text)
                ?: throw UsageException("--document is platform, app:<id> or tenant:<id>, not [${Identifiers.printable(text)}].")
        }
        val limit = options.value("limit")?.let { text ->
            text.toIntOrNull()?.takeIf { it > 0 } ?: throw UsageException("--limit is a positive number, not [$text].")
        }
        val scrub = { text: String -> RedisUris.scrub(text, options.redisUris) }
        val verified = controlStore(options) { store ->
            ChainVerification(store).run(only, options.value("prefix"), limit) { chain -> printChain(chain, out, scrub) }
        }
        return summary(verified, out)
    }

    /** One document's lines, printed as it is verified (R2C-20). */
    private fun printChain(chain: VerifiedChain, out: PrintStream, scrub: (String) -> String) {
        out.println(lineOf(chain, scrub))
        indexLine(chain)?.let(out::println)
    }

    /**
     * Prints what [verified] found, one line per document and a summary, and answers the exit code; [scrub] leaves the
     * credentials of the URIs given out of what a failure prints.
     */
    fun report(verified: List<VerifiedChain>, out: PrintStream, scrub: (String) -> String = { it }): Int {
        verified.forEach { printChain(it, out, scrub) }
        return summary(verified, out)
    }

    /** The summary of [verified] and the exit code. */
    private fun summary(verified: List<VerifiedChain>, out: PrintStream): Int {
        val broken = verified.count { it.check is ChainCheck.Broken }
        val failed = verified.count { it.failure != null }
        val differs = verified.count { it.index is IndexCheck.Differs }
        val indexes = if (differs > 0) ", $differs with a revision index that does not follow the head's chain" else ""
        out.println("Verified ${verified.size} document(s): $broken broken, $failed could not be read$indexes.")
        return if (broken + failed + differs > 0) FAILED else OK
    }

    /** The finding on the revision index of [verified], if there is one; a missing chain says nothing of its index. */
    private fun indexLine(verified: VerifiedChain): String? = when (val index = verified.index) {
        is IndexCheck.Differs ->
            "INDEX ${verified.key}: the revision index does not follow the head's chain: ${index.why}; the next read " +
                "of the document's history repairs it"
        is IndexCheck.Behind ->
            "note ${verified.key}: the revision index is one revision behind the head (revision ${index.revision}): a " +
                "publication that is settling, or one whose index write failed; the next read of the history adds it"
        is IndexCheck.Unchecked ->
            "note ${verified.key}: ${index.entries} revision index entries predate the parent field and cannot be " +
                "checked; the next read of the document's history rewrites them"
        else -> null
    }

    private fun lineOf(verified: VerifiedChain, scrub: (String) -> String): String =
        when (val check = verified.check) {
            is ChainCheck.Intact ->
                if (check.oldest == 0L) {
                    "ok ${verified.key}: no head"
                } else {
                    "ok ${verified.key}: intact down to revision ${check.oldest}"
                }
            is ChainCheck.Broken ->
                "BROKEN ${verified.key}: no record holds revision ${check.missing.revision} (${check.missing.digest}): " +
                    "a write lost in a Redis failover, or a deleted key"
            null -> "FAILED ${verified.key}: ${scrub(verified.failure.orEmpty())}"
        }
}
