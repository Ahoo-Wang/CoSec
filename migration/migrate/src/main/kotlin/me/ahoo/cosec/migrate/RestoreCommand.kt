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
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.migrate.MigrateMain.FAILED
import me.ahoo.cosec.migrate.MigrateMain.OK
import me.ahoo.cosec.migrate.MigrateMain.USAGE
import me.ahoo.cosec.migrate.RedisAccess.controlStore
import me.ahoo.cosec.model.Identifiers
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * `restore` (UC-6, [DocumentRestorer]): the way back from a lock-out, not a way to publish. Without `--confirm` it
 * prints what it would replace and writes nothing (exit 2); with `--confirm <head revision>` it writes, when that is
 * still the head.
 */
internal object RestoreCommand {
    fun restore(options: Options, out: PrintStream): Int {
        val key = key(options.required("document"))
        val revision = number(options, "revision", "a number")
        val confirm = number(options, "confirm", "the head revision")
        val file = options.value("file")?.let { read(key, Path.of(it)) }
        if ((revision == null) == (file == null)) {
            throw UsageException("restore takes one of --revision <n> and --file <document.json>.")
        }
        return try {
            controlStore(options) { store ->
                val restorer = DocumentRestorer(store)
                val plan = restorer.plan(key, revision, file)
                out.println(
                    "Restoring [$key]: its head, revision ${plan.head.revision}, is replaced by the content of " +
                        "${plan.source}, written as revision ${plan.head.revision + 1}.",
                )
                if (confirm == null) {
                    out.println("Nothing written. To write it: the same command with --confirm ${plan.head.revision}.")
                    USAGE
                } else {
                    out.println("Restored [$key] as revision ${restorer.restore(plan, confirm)}.")
                    OK
                }
            }
        } catch (error: RestoreException) {
            out.println("Refused: ${RedisUris.scrub(error.message.orEmpty(), options.redisUris)}")
            FAILED
        }
    }

    private fun key(text: String): SnapshotKey = PublishCommand.documentKey(text)
        ?: throw UsageException("--document is platform, app:<id> or tenant:<id>, not [${Identifiers.printable(text)}].")

    private fun number(options: Options, name: String, what: String): Long? =
        options.value(name)?.let { it.toLongOrNull() ?: throw UsageException("--$name is $what, not [$it].") }

    private fun read(key: SnapshotKey, path: Path) = run {
        if (!Files.isRegularFile(path)) throw UsageException("$path is not a file.")
        val (kind, owner) = me.ahoo.cosec.control.change.ownerOf(key)
        PolicyDocumentCodec.decode(kind, owner, Files.readAllBytes(path))
    }
}
