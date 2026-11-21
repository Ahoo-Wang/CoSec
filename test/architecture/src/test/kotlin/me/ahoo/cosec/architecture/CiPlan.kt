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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile

/** The plan CI runs, from the script itself, which also fails when the groups and `codecov.yml` disagree. */
object CiPlan {
    /** The plan of a push: every group, and the project graph the script reads from the build scripts. */
    val plan: JsonNode by lazy {
        val process = ProcessBuilder(
            python().toString(),
            "-I",
            "scripts/ci/plan.py",
            "--root",
            Projects.root.toString()
        )
            .directory(Projects.root.toFile())
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(1, TimeUnit.MINUTES).assert().isTrue()
        process.exitValue().assert().describedAs(output).isZero()
        JsonMapper.builder().build().readTree(output)
    }

    /** The interpreter as an absolute path, found on PATH once: a command named by a relative path is whatever the directory has. */
    private fun python(): Path = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
        .filter { it.isNotBlank() }
        .map { Path.of(it).toAbsolutePath().resolve("python3") }
        .firstOrNull { it.isRegularFile() && it.isExecutable() }
        ?: error("python3 is not on PATH: CI's plan is a Python script")
}
