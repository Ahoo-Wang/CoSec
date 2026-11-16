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
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readLines

/**
 * The build resolves from Maven Central only: a jar in `~/.m2` with the coordinates of a published one would shadow it on
 * one machine and not on CI. `mavenLocal()` is allowed in a build script only directly under an
 * `if (...cosec.mavenLocal...)`, the property that asks for it (CONTRIBUTING.md).
 */
class MavenLocalGuardTest {
    @Test
    fun `no build script resolves from mavenLocal unless asked`() {
        val offenders = unguardedIn(buildScripts())
        offenders.assert().describedAs(
            "`mavenLocal()` in a build script outside `if (providers.gradleProperty(\"cosec.mavenLocal\").isPresent)`"
        ).isEmpty()
    }

    @Test
    fun `the guard sees the scripts and the one guarded call`() {
        val scripts = buildScripts()
        scripts.map { it.name }.assert().contains("build.gradle.kts", "settings.gradle.kts")
        scripts.count { path -> path.readLines().any { MAVEN_LOCAL in it } }.assert().isEqualTo(1)
    }

    @Test
    fun `it fails on an unconditional call and on one under another condition`() {
        unguarded("repositories {\n    mavenLocal()\n    mavenCentral()\n}".lines()).assert().hasSize(1)
        unguarded(
            "if (providers.gradleProperty(\"other\").isPresent) {\n    mavenLocal()\n}".lines()
        ).assert().hasSize(1)
        unguarded("mavenLocal() // first".lines()).assert().hasSize(1)
        unguarded("if (providers.gradleProperty(\"cosec.mavenLocal\").isPresent) {\n    mavenLocal()\n}".lines())
            .assert().isEmpty()
        unguarded(
            "if (providers.gradleProperty(\"cosec.mavenLocal\").isPresent) {\n    // first\n\n    mavenLocal()\n}".lines()
        ).assert().isEmpty()
    }

    private fun buildScripts(): List<Path> = Files.walk(Projects.root).use { paths ->
        paths.filter { path ->
            path.name.endsWith(".gradle.kts") && Projects.root.relativize(path).none { it.name in SKIPPED }
        }.toList()
    }

    private fun unguardedIn(scripts: List<Path>): List<String> = scripts.flatMap { script ->
        unguarded(script.readLines()).map { "${Projects.root.relativize(script)}: $it" }
    }

    private fun unguarded(lines: List<String>): List<String> = lines.mapIndexedNotNull { index, line ->
        if (MAVEN_LOCAL !in line) {
            return@mapIndexedNotNull null
        }
        val before = lines.take(index).lastOrNull { it.isNotBlank() && !it.trim().startsWith("//") }
        line.trim().takeUnless { before != null && GUARD.containsMatchIn(before) }
    }

    private companion object {
        const val MAVEN_LOCAL = "mavenLocal()"
        val SKIPPED = setOf("build", ".gradle", ".git", ".claude", "node_modules")
        val GUARD = Regex("""^\s*if \(.*"cosec\.mavenLocal".*\) \{\s*$""")
    }
}
