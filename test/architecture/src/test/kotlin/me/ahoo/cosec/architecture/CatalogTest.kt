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
import kotlin.io.path.exists
import kotlin.io.path.readText

/** G3: every entry of the version catalog is used, so the catalog never lists a dependency nothing pulls in. */
class CatalogTest {
    private val catalog = Projects.root.resolve("gradle/libs.versions.toml").readText()

    /** Every `libs.…` accessor the build scripts use: the settings, the root's and every project's. */
    private val accessors: Set<String> = (Projects.directories.values + listOf(Projects.root))
        .map { it.resolve("build.gradle.kts") }
        .plus(Projects.root.resolve("settings.gradle.kts"))
        .filter { it.exists() }
        .flatMap { script -> Regex("""libs\.[A-Za-z0-9_.]+""").findAll(script.readText()).map { it.value } }
        .toSet()

    private fun section(name: String): List<String> =
        Regex("""(?ms)^\[$name]\n(.*?)(?=^\[|\z)""").find(catalog)!!.groupValues[1].lines()
            .mapNotNull { Regex("""^([A-Za-z0-9_.-]+)\s*=""").find(it)?.groupValues?.get(1) }

    private fun accessorOf(alias: String): String = alias.replace('-', '.').replace('_', '.')

    @Test
    fun `every library is used by a build script`() {
        section("libraries").filterNot { "libs.${accessorOf(it)}" in accessors }.assert().isEmpty()
    }

    @Test
    fun `every plugin is used by a build script`() {
        section("plugins").filterNot { "libs.plugins.${accessorOf(it)}" in accessors }.assert().isEmpty()
    }

    @Test
    fun `every version is referenced`() {
        section("versions").filterNot {
            """version.ref = "$it"""" in catalog || "libs.versions.${accessorOf(it)}" in accessors
        }.assert().isEmpty()
    }
}
