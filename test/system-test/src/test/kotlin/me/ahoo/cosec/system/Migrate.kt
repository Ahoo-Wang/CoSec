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

package me.ahoo.cosec.system

import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * `cosec-migrate`, built from the commit under test: the documented way to seed an empty Redis (the first platform
 * document cannot come through the control plane, which it authorizes; `wiki/operations/storage.md`).
 */
object Migrate {
    private const val TIMEOUT_SECONDS = 120L

    /** Publishes the hand-written [document] (a file of `test/system-test/documents/`) as [key]: `platform`, `app:<id>`, ... */
    fun publish(document: String, key: String) {
        run(
            "publish",
            "--to-redis",
            SystemTopology.redis,
            "--file",
            SystemTopology.documents.resolve(document).toString(),
            "--document",
            key
        )
    }

    /** Names the first platform administrator: [principal] of the tenant `(platform)` holds `cosec:platform-admin`. */
    fun bootstrap(principal: String) {
        run("bootstrap", "--to-redis", SystemTopology.redis, "--platform-admin", principal)
    }

    private fun run(vararg arguments: String) {
        val command = listOf(SystemTopology.migrate.toString()) + arguments
        Transcript.note("$ ${command.joinToString(" ")}")
        val log = Files.createTempFile("cosec-migrate", ".log")
        val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
        }
        val output = Files.readString(log).also { Files.delete(log) }
        Transcript.note(output)
        check(finished) { "cosec-migrate did not end within $TIMEOUT_SECONDS s: $output" }
        check(process.exitValue() == 0) { "cosec-migrate exited with ${process.exitValue()}: $output" }
    }
}
