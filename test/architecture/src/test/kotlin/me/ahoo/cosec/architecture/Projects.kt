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

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.readLines

/**
 * Where each Gradle project sits, from the build (`cosec.projects`, `name=directory;…`) rather than from a path
 * the rules assume, and the repository root (`cosec.repository.root`).
 */
object Projects {
    val root: Path = Path.of(property("cosec.repository.root"))

    val directories: Map<String, Path> = property("cosec.projects").split(';').filter { it.isNotBlank() }.associate {
        val (name, directory) = it.split('=', limit = 2)
        name to Path.of(directory)
    }

    fun directory(name: String): Path = requireNotNull(directories[name]) { "No project :$name in the build." }

    /** The group of [project] in the project list (RFC 0011 §3). */
    fun groupOf(project: String): String =
        ProjectListTest.GROUPS.entries.single { project in it.value }.key

    /** Every package declared in a project's main sources, with the projects that declare it. */
    val mainPackages: Map<String, Set<String>> by lazy {
        val packages = mutableMapOf<String, MutableSet<String>>()
        for ((project, directory) in directories) {
            val main = directory.resolve("src/main")
            if (!main.exists()) continue
            Files.walk(main).use { files ->
                files.filter { it.extension == "kt" || it.extension == "java" }.forEach { file ->
                    file.readLines().firstOrNull { it.startsWith("package ") }?.let { line ->
                        packages.getOrPut(line.removePrefix("package ").trim().removeSuffix(";")) { mutableSetOf() }
                            .add(project)
                    }
                }
            }
        }
        packages
    }

    /** The project whose main sources declare [packageName], if exactly one does. */
    fun projectOfPackage(packageName: String): String? = mainPackages[packageName]?.singleOrNull()

    /** Each project's declared and resolved dependencies, as the root build's `architectureFacts` wrote them. */
    val facts: Map<String, List<String>> by lazy {
        property("cosec.facts").split(';').filter { it.isNotBlank() }.associate {
            val (name, file) = it.split('=', limit = 2)
            val path = Path.of(file)
            name to if (path.exists()) path.readLines().filter { line -> line.isNotBlank() } else emptyList()
        }
    }

    private fun property(name: String): String =
        requireNotNull(System.getProperty(name)) { "$name is not set: run the rules through Gradle." }
}
