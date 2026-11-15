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

import org.w3c.dom.Element
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What a published module tells a build that depends on it: its POM (what Maven reads) and its Gradle module metadata
 * (what Gradle reads), parsed into the facts the rules in [PublishedMetadataRules] need. The files are the ones the
 * build generates for publication, so a rule reads what an application would see, not what the build declares.
 */
class PublishedMetadata(
    /** The artifact id, `cosec-runtime`. */
    val artifact: String,
    /** The POM's own `<dependencies>`. */
    val pomDependencies: List<PomDependency>,
    /** The POM's `<dependencyManagement>` entries. */
    val pomManaged: List<PomDependency>,
    /** The dependencies of each Gradle variant, by variant name (`apiElements`, `runtimeElements`, …). */
    val variants: Map<String, List<ModuleDependency>>,
    /** The constraints of each Gradle variant, by variant name: what a BOM lists. */
    val constraints: Map<String, List<ModuleDependency>>,
    /** Both files as text, for a rule that looks for a name anywhere in them. */
    val text: String,
) {
    class PomDependency(
        val group: String,
        val name: String,
        val version: String?,
        val scope: String?,
        val optional: Boolean,
        val type: String?,
    ) {
        val id: String get() = "$group:$name"

        /** Maven brings it to an application's run time: compile or runtime scope, and not optional. */
        val atRuntime: Boolean get() = !optional && (scope == null || scope == "compile" || scope == "runtime") &&
            type != "pom"
    }

    class ModuleDependency(
        val group: String,
        val name: String,
        /** `requires`, `strictly` or `prefers`: whichever the metadata gives, null when it gives none. */
        val version: String?,
        /** `platform` or `enforced-platform` when the dependency is a platform. */
        val category: String?,
    ) {
        val id: String get() = "$group:$name"
    }

    companion object {
        private val json = JsonMapper.builder().build()

        fun parse(artifact: String, pom: String, module: String): PublishedMetadata {
            val project = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(pom.byteInputStream()).documentElement
            val tree = json.readTree(module)
            return PublishedMetadata(
                artifact = artifact,
                pomDependencies = children(
                    project,
                    "dependencies"
                ).flatMap { children(it, "dependency") }.map(::pomDependency),
                pomManaged = children(project, "dependencyManagement").flatMap { children(it, "dependencies") }
                    .flatMap { children(it, "dependency") }.map(::pomDependency),
                variants = variants(tree, "dependencies"),
                constraints = variants(tree, "dependencyConstraints"),
                text = pom + "\n" + module,
            )
        }

        private fun children(parent: Element, tag: String): List<Element> =
            (0 until parent.childNodes.length).map { parent.childNodes.item(it) }
                .filterIsInstance<Element>().filter { it.tagName == tag }

        private fun text(parent: Element, tag: String): String? = children(
            parent,
            tag
        ).firstOrNull()?.textContent?.trim()

        private fun pomDependency(element: Element) = PomDependency(
            group = text(element, "groupId").orEmpty(),
            name = text(element, "artifactId").orEmpty(),
            version = text(element, "version"),
            scope = text(element, "scope"),
            optional = text(element, "optional") == "true",
            type = text(element, "type"),
        )

        private fun variants(tree: JsonNode, field: String): Map<String, List<ModuleDependency>> =
            tree.path("variants").values().associate { variant ->
                variant.path("name").asString() to variant.path(field).values().map { dependency ->
                    val version = dependency.path("version")
                    ModuleDependency(
                        group = dependency.path("group").asString(),
                        name = dependency.path("module").asString(),
                        version = listOf("requires", "strictly", "prefers")
                            .firstNotNullOfOrNull { version.path(it).asString("").ifEmpty { null } },
                        category = dependency.path("attributes").path("org.gradle.category").asString("").ifEmpty { null },
                    )
                }
            }
    }
}
