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

package me.ahoo.cosec.conformance

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** How the tests find `git` and the Gradle wrapper, on Unix and on Windows, wherever they run. */
class ExecutablesTest {
    @TempDir
    lateinit var temp: Path

    private fun bin(name: String, vararg files: String): Path {
        val directory = Files.createDirectories(temp.resolve(name))
        files.forEach { Files.createFile(directory.resolve(it)) }
        return directory
    }

    @Test
    fun `on Windows a program is found by its PATHEXT extension`() {
        val empty = bin("empty")
        val tools = bin("tools", "git.EXE")
        val path = listOf(empty, tools).joinToString(File.pathSeparator)
        val extensions = Executables.extensions(windows = true, pathExt = ".COM;.EXE;.BAT")
        extensions.assert().containsExactly(".COM", ".EXE", ".BAT")
        Executables.find("git", path, extensions, Files::exists).assert().isEqualTo(tools.resolve("git.EXE"))
        Executables.extensions(windows = true, pathExt = null).assert().contains(".EXE", ".CMD")
    }

    @Test
    fun `elsewhere the name is the file`() {
        val tools = bin("unix", "git", "git.exe")
        Executables.extensions(windows = false, pathExt = ".EXE").assert().containsExactly("")
        Executables.find("git", tools.toString(), listOf(""), Files::exists).assert().isEqualTo(tools.resolve("git"))
        Executables.find("svn", tools.toString(), listOf(""), Files::exists).assert().isNull()
    }

    @Test
    fun `the wrapper is the batch file on Windows`() {
        Executables.gradleWrapper(temp, windows = true).assert().endsWith("gradlew.bat")
        Executables.gradleWrapper(temp, windows = false).assert().endsWith("${File.separator}gradlew")
    }

    @Test
    fun `git is found on this machine`() {
        Executables.find("git").assert().isNotNull()
    }
}
