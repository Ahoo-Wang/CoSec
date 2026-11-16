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
import me.ahoo.cosec.migrate.MigrateMain.FAILED
import me.ahoo.cosec.migrate.MigrateMain.OK
import me.ahoo.cosec.migrate.RedisAccess.controlStore
import me.ahoo.cosec.model.Identifiers
import java.io.PrintStream

/** `verify-chain`. */
internal object VerifyChainCommand {
    /** `verify-chain`: one line per document; a broken chain, or one that cannot be read, exits with [FAILED]. */
    fun verifyChain(options: Options, out: PrintStream): Int {
        val only = options.value("key")?.let { text ->
            PublishCommand.documentKey(text)
                ?: throw UsageException("--key is platform, app:<id> or tenant:<id>, not [${Identifiers.printable(text)}].")
        }
        val verified = controlStore(options) { store -> ChainVerification(store).run(only) }
        return report(verified, out) { RedisUris.scrub(it, options.redisUris) }
    }

    /**
     * Prints what [verified] found, one line per document and a summary, and answers the exit code; [scrub] leaves the
     * credentials of the URIs given out of what a failure prints.
     */
    fun report(verified: List<VerifiedChain>, out: PrintStream, scrub: (String) -> String = { it }): Int {
        verified.forEach { out.println(lineOf(it, scrub)) }
        val broken = verified.count { it.check is ChainCheck.Broken }
        val failed = verified.count { it.failure != null }
        out.println("Verified ${verified.size} document(s): $broken broken, $failed could not be read.")
        return if (broken + failed > 0) FAILED else OK
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
