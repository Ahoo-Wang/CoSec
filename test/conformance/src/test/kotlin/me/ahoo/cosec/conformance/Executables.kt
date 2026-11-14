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

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path

/**
 * A program found as a shell finds it, for the tests and tasks that start one: on the `PATH`, by its absolute path, so
 * what runs is the binary that was found. On Windows a program's file carries one of the `PATHEXT` extensions
 * (`git.exe`), so each is tried; elsewhere the name is the file.
 */
internal object Executables {
    private val windows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

    /** Windows' defaults, when `PATHEXT` is not set. */
    private const val DEFAULT_PATHEXT = ".COM;.EXE;.BAT;.CMD"

    fun find(
        name: String,
        path: String = System.getenv("PATH").orEmpty(),
        extensions: List<String> = extensions(),
        isExecutable: (Path) -> Boolean = Files::isExecutable,
    ): Path? = path.split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .flatMap { directory -> extensions.map { Path(directory, name + it) } }
        .firstOrNull(isExecutable)
        ?.toAbsolutePath()

    fun extensions(windows: Boolean = this.windows, pathExt: String? = System.getenv("PATHEXT")): List<String> =
        if (windows) {
            (pathExt ?: DEFAULT_PATHEXT).split(';').map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            listOf("")
        }

    /** The Gradle wrapper of the build in [root]: `gradlew.bat` on Windows, `gradlew` elsewhere. */
    fun gradleWrapper(root: Path, windows: Boolean = this.windows): String =
        root.resolve(if (windows) "gradlew.bat" else "gradlew").toAbsolutePath().toString()
}
