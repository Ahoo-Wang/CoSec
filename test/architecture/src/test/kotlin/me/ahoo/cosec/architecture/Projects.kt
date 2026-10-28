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

import java.nio.file.Path

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

    private fun property(name: String): String =
        requireNotNull(System.getProperty(name)) { "$name is not set: run the rules through Gradle." }
}
