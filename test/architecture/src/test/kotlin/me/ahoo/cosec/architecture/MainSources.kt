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

import kotlin.io.path.readLines

/** A main source file of a library project, by its path from the repository root, with its lines. */
class MainSource(val path: String, val lines: List<String>)

/**
 * The main sources (`src/main`, Kotlin and Java) of every library project, the root build's `libraryProjects`:
 * what the structure guards of RFC 0013 §8.1 measure. Read from disk, so a project off this class path counts too.
 */
object MainSources {
    val libraryProjects: List<String> = requireNotNull(System.getProperty("cosec.coverage.projects")) {
        "cosec.coverage.projects is not set: run the rules through Gradle."
    }.split(';').filter { it.isNotBlank() }.sorted()

    val files: List<MainSource> by lazy {
        libraryProjects.flatMap { project ->
            SourceRulesTest.mainSources(project).map { file ->
                MainSource(Projects.root.relativize(file).joinToString("/"), file.readLines())
            }
        }.sortedBy { it.path }
    }
}
