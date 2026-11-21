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

import me.ahoo.test.asserts.assert
import java.util.concurrent.TimeUnit

/**
 * The faults of the system test: `docker compose stop`, `start` and `restart` of a service of `compose.yaml`, which the
 * workflow's environment names (`COMPOSE_FILE`). The only way a scenario touches a process other than over the network,
 * as an operator or an outage would.
 */
object Compose {
    private const val TIMEOUT_SECONDS = 180L

    fun stop(service: String) = run("stop", service)

    fun start(service: String) = run("start", service)

    fun restart(service: String) = run("restart", service)

    /** Runs [command] in the running [service] (`docker compose exec`) and returns what it printed; fails when it exits non-zero. */
    fun exec(service: String, vararg command: String): String = run("exec", "-T", service, *command)

    /** Like [exec], but whatever the command's exit code: a console consumer that read nothing before its timeout exits 1. */
    fun execAnyway(
        service: String,
        vararg command: String
    ): String = run("exec", "-T", service, *command, check = false)

    /**
     * A one-off container of [service] (`docker compose run`) with [environment] over the service's own, no ports published
     * and no dependency started: what it ended with, the exit code and everything it printed. It is named [name] and
     * removed at the end, whether it ended or was still running at the timeout.
     */
    fun runOnce(service: String, name: String, environment: Map<String, String>): Pair<Int, String> {
        val command = listOf("docker", "compose", "run", "--rm", "--no-deps", "-T", "--name", name) +
            environment.flatMap { (key, value) -> listOf("-e", "$key=$value") } + service
        Transcript.note("$ ${command.joinToString(" ")}")
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread { output.append(process.inputStream.bufferedReader().readText()) }.apply { start() }
        try {
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
            }
            reader.join(10_000)
            Transcript.note(output.toString())
            finished.assert().describedAs("$service ($name) did not end in $TIMEOUT_SECONDS s: $output").isTrue()
            return process.exitValue() to output.toString()
        } finally {
            val remove = listOf("docker", "rm", "--force", name)
            ProcessBuilder(remove).redirectErrorStream(true).start().waitFor(60, TimeUnit.SECONDS)
        }
    }

    private fun run(verb: String, vararg arguments: String, check: Boolean = true): String {
        val service = arguments.joinToString(" ")
        val command = listOf("docker", "compose", verb) + arguments
        Transcript.note("$ ${command.joinToString(" ")}")
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        Transcript.note(output)
        finished.assert().describedAs(
            "docker compose $verb $service did not end in $TIMEOUT_SECONDS s: $output"
        ).isTrue()
        if (check) process.exitValue().assert().describedAs("docker compose $verb $service: $output").isEqualTo(0)
        return output
    }
}
