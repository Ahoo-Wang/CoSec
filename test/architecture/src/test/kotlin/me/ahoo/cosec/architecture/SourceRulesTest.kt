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

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.relativeTo

/**
 * RFC 0011 §5, the source rules: what main code may reference, by the group its project is in, and where the opt-in to
 * CoSec's internal API may appear (D3).
 */
class SourceRulesTest {
    companion object {
        val CLASSES: JavaClasses by lazy {
            ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests()).importPackages("me.ahoo.cosec")
        }

        /** Reactor stays at the bridges (§5): edge, extensions and the host. */
        private val NO_REACTOR = setOf("api", "core", "backends")

        /** Reactor a backend takes from its own client, with the reason: the non-blocking Redis reads return it. */
        private val REACTOR_TECHNOLOGY: Map<String, String> = mapOf(
            "cocache" to "Spring Data Redis's reactive API is the non-blocking Redis client the suspending SPIs await",
        )

        /** The Spring libraries a backend or an extension uses as its own technology; the framework itself stays out. */
        private val SPRING_TECHNOLOGY: Map<String, List<String>> = mapOf(
            "cocache" to listOf("org.springframework.data.redis.", "org.springframework.aot.hint."),
            "kafka" to listOf("org.springframework.kafka.", "org.springframework.beans.factory.DisposableBean"),
            "social" to listOf("org.springframework.data.redis."),
        )

        /** Servlet API and Redis clients: never in the contracts or the core. */
        private val NOT_IN_CORE = listOf("jakarta.servlet.", "io.lettuce.", "redis.clients.", "org.springframework.")

        /**
         * What `cocache` and `kafka` may use of `runtime` and `control` (#1140, and the mapper entry): package of the
         * using class to the classes or packages it may reach.
         */
        val BACKEND_REACH: Map<String, List<String>> = mapOf(
            "me.ahoo.cosec.cache.shadow" to listOf("me.ahoo.cosec.runtime.shadow."),
            "me.ahoo.cosec.cache.control" to listOf("me.ahoo.cosec.control.", "me.ahoo.cosec.runtime.shadow.StoredCandidate"),
            "me.ahoo.cosec.kafka" to listOf(
                "me.ahoo.cosec.runtime.event.",
                "me.ahoo.cosec.runtime.audit.AuditEvents",
                "me.ahoo.cosec.control.audit.",
            ),
        )

        /** The one entry every backend class has (the mapper allowlist entry): the mapper factory and nothing else. */
        const val MAPPER = "me.ahoo.cosec.runtime.json.CoSecJson"

        fun projectOf(javaClass: JavaClass): String? = Projects.projectOfPackage(javaClass.packageName)
    }

    private fun violations(rule: (project: String, target: String) -> Boolean): List<String> =
        CLASSES.flatMap { origin ->
            val project = projectOf(origin) ?: return@flatMap emptyList()
            origin.directDependenciesFromSelf.map { it.targetClass.name }.distinct()
                .filter { rule(project, it) }
                .map { "${origin.name} -> $it" }
        }

    @Test
    fun `Reactor only at the bridges`() {
        violations { project, target ->
            Projects.groupOf(project) in NO_REACTOR && project !in REACTOR_TECHNOLOGY && target.startsWith("reactor.")
        }.assert().isEmpty()
    }

    @Test
    fun `no Spring, Servlet API or Redis client in the contracts and the core`() {
        violations { project, target ->
            Projects.groupOf(project) in setOf("api", "core") && NOT_IN_CORE.any { target.startsWith(it) }
        }.assert().isEmpty()
    }

    @Test
    fun `backends and extensions use only their own technology of Spring`() {
        violations { project, target ->
            Projects.groupOf(project) in setOf("backends", "extensions") &&
                target.startsWith("org.springframework.") &&
                SPRING_TECHNOLOGY[project].orEmpty().none { target.startsWith(it) }
        }.assert().isEmpty()
    }

    @Test
    fun `backends reach into runtime and control only through the listed ports and the mapper`() {
        violations { project, target ->
            Projects.groupOf(project) == "backends" &&
                (target.startsWith("me.ahoo.cosec.runtime.") || target.startsWith("me.ahoo.cosec.control.")) &&
                target != MAPPER && !target.startsWith("$MAPPER\$")
        }.filterNot { violation ->
            val origin = violation.substringBefore(" -> ")
            val target = violation.substringAfter(" -> ")
            BACKEND_REACH.any { (from, to) ->
                origin.substringBeforeLast('.') == from && to.any { target.startsWith(it) }
            }
        }.assert().isEmpty()
    }

    @Test
    fun `a package belongs to one module`() {
        Projects.mainPackages.filterValues { it.size > 1 }.assert().isEmpty()
    }

    @Test
    fun `only CoSec's own sources opt in to the internal API`() {
        val optIn = Regex(
            """OptIn\(\s*(me\.ahoo\.cosec\.annotations\.)?InternalCoSecApi::class|opt-in=me\.ahoo\.cosec\.annotations\.InternalCoSecApi"""
        )
        val skipped = setOf("build", "node_modules", ".git", ".gradle", ".vitepress")
        val violations = Files.walk(Projects.root).use { paths ->
            paths.filter { path -> Files.isRegularFile(path) }
                .filter { path -> Projects.root.relativize(path).none { it.name in skipped } }
                .filter { it.extension in setOf("kt", "kts", "java", "md", "txt", "yml", "yaml") }
                .filter { optIn.containsMatchIn(it.readText()) }
                .map { it.relativeTo(Projects.root).toString() }
                .toList()
        }.filterNot { file ->
            val owner = Projects.directories.entries.firstOrNull { (_, directory) ->
                Projects.root.resolve(file).startsWith(directory)
            }?.key
            owner != null && Projects.groupOf(owner) != "platform" && file.endsWith(".kt") &&
                "/src/" in file && Projects.root.resolve(file).readText().contains(Regex("""(?m)^package me\.ahoo\.cosec\b"""))
        }
        violations.assert().describedAs("opt-ins outside CoSec's own Kotlin sources").isEmpty()
    }
}
