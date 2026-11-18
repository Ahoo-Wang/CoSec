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

    private fun versionOf(alias: String): String =
        Regex("""(?m)^${Regex.escape(alias)}\s*=\s*"([^"]+)"""").find(catalog)!!.groupValues[1]

    /**
     * The page that says what CoSec needs from an application's build names what does not move with every patch: the
     * Spring Boot minor line, CoCache's major version and the JDK. A patch version is in the published metadata and
     * would be stale after the next dependency update; a minor line or a JDK that moves is a change the page must follow.
     */
    @Test
    fun `the build requirements page names the catalog's Spring Boot line, CoCache major and JDK`() {
        val boot = versionOf("spring-boot").split('.').take(2).joinToString(".")
        val cocache = versionOf("cocache").substringBefore('.')
        val jdk = Regex(
            """jvmToolchain\((\d+)\)"""
        ).find(Projects.root.resolve("build.gradle.kts").readText())!!.groupValues[1]
        mapOf(
            "wiki/guide/introduction.md" to "## What CoSec needs from your build",
            "wiki/zh/guide/introduction.md" to "## CoSec 对构建的要求",
        ).forEach { (page, heading) ->
            val section = Projects.root.resolve(page).readText().substringAfter(heading).substringBefore("\n## ")
            listOf("Spring Boot $boot", "CoCache $cocache", "JDK $jdk").filterNot { it in section }
                .map { "$page: does not say \"$it\"" }.assert().isEmpty()
        }
    }
}
