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

package me.ahoo.cosec.architecture

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import kotlin.io.path.readLines

/**
 * DR1-G-8: in each image the process owns only `logs/` and `data/`. The jars, the start script and the AOT cache are
 * root's and not writable by it, so a process made to write files cannot replace the code its next start runs, whether
 * or not the root filesystem is mounted read-only (the Kubernetes manifests mount it so; `docker run` does not).
 */
class ImageFilesTest {
    private val images = listOf("platform/gateway-server/Dockerfile", "platform/control-server/Dockerfile")

    /** The instructions of the stage named [stage], continuation lines joined. */
    private fun stage(file: String, stage: String): List<String> {
        val instructions = Projects.root.resolve(file).readLines()
            .filterNot { it.trimStart().startsWith("#") }
            .joinToString("\n").replace("\\\n", " ")
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
        val start = instructions.indexOfFirst { it.startsWith("FROM ") && it.endsWith(" AS $stage") }
        start.assert().describedAs("$file has no stage $stage").isNotNegative()
        val end = instructions.drop(start + 1).indexOfFirst { it.startsWith("FROM ") }
        return instructions.subList(start + 1, if (end < 0) instructions.size else start + 1 + end)
    }

    @Test
    fun `the application's files are root's, and the process owns only its logs and data`() {
        for (file in images) {
            val run = stage(file, "run")
            run.filter { it.startsWith("COPY") && "--chown" in it }.assert().describedAs(file).isEmpty()
            val user = run.indexOfFirst { it == "USER appuser" }
            user.assert().describedAs("$file never drops root").isNotNegative()
            val asRoot = run.subList(0, user)
            asRoot.filter { "chown" in it }.flatMap { Regex("chown (\\S+) (.+)").findAll(it).toList() }
                .map { it.groupValues[2].trim() }
                .assert().describedAs(file).containsExactly("logs data")
            run.subList(user, run.size).filter { it.startsWith("COPY") || it.startsWith("RUN ") }
                .assert().describedAs("$file writes files as the process").isEmpty()
        }
    }

    @Test
    fun `nothing in the application's directory is writable but by its owner`() {
        for (file in images) {
            stage(file, "build").assert().describedAs(file)
                .anyMatch { it.startsWith("RUN chmod -R u=rwX,go=rX .") }
        }
    }
}
