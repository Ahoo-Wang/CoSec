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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.spring.boot

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Every `COSEC_*` variable the repository documents or ships is the variable of a property CoSec reads. Spring maps
 * each `_` to a `.` and drops the dashes of a key, so `cosec.authorization.snapshot.eviction-check` is
 * `COSEC_AUTHORIZATION_SNAPSHOT_EVICTIONCHECK`; the spelling that reads naturally, `..._EVICTION_CHECK`, is the key
 * `eviction.check`, which the starter refuses at startup ([UnknownProperties]). A page, a manifest or a message that
 * wrote it that way sent an operator to a crash loop, so each variable is asked of the starter's own check, on the
 * metadata it generates.
 *
 * What is scanned: the wiki (both languages), `skills/`, `k8s/`, `platform/`, `test/system-*` (their files, not their
 * test sources, which are the harness's), the root documents, and the string literals of the starter's main sources
 * (its messages). Variables that are not properties are listed below with their reason, and the deliberately wrong
 * ones are listed too, and must be refused: the list cannot excuse a real variable.
 */
class EnvironmentVariableSpellingTest {
    private val repository: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("settings.gradle.kts")) }

    private val check = UnknownProperties.load(javaClass.classLoader)

    private fun environment(variable: String) = StandardEnvironment().apply {
        propertySources.replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                mapOf(variable to "x")
            )
        )
    }

    private fun refused(variable: String): List<String> = check.of(environment(variable))

    /** The variables [text] names, and for Kotlin only those inside a string literal: its comments and constants are not messages. */
    private fun variables(file: Path, text: String): Set<String> {
        val scanned = if (file.extension == "kt") LITERAL.findAll(text).joinToString("\n") { it.value } else text
        return VARIABLE.findAll(scanned).map { it.value }.toSet()
    }

    private fun scannedFiles(): List<Path> {
        val roots = listOf("wiki", "skills", "k8s", "platform", "host/spring-boot/src/main") +
            repository.resolve("test").toFile().listFiles().orEmpty().filter { it.name.startsWith("system-") }
                .map { "test/${it.name}" }
        val files = roots.map { repository.resolve(it) }.filter { Files.exists(it) }.flatMap { root ->
            Files.walk(root).use { paths ->
                paths.filter { path ->
                    !path.isDirectory() && root.relativize(path).none { it.name in SKIPPED_DIRECTORIES } &&
                        !path.toString().contains("/src/test/") &&
                        (path.extension in EXTENSIONS || path.name == "Dockerfile")
                }.toList()
            }
        }
        val rootDocuments = Files.list(repository).use { paths ->
            paths.filter { it.extension == "md" || it.name == "llms.txt" }.toList()
        }
        return files + rootDocuments
    }

    /** Every variable that is not a property and not excused, as `file: VARIABLE (what the starter reads it as)`. */
    private fun problems(files: Map<Path, String>): List<String> = files.flatMap { (file, text) ->
        variables(file, text).filterNot { excused(it) }.mapNotNull { variable ->
            refused(variable).firstOrNull()?.let { key ->
                "${repository.relativize(file)}: $variable is read as $key, which no property is" +
                    (check.suggestion(key)?.let { " ($it)" } ?: "")
            }
        }
    }.sorted()

    private fun excused(variable: String): Boolean =
        variable in NOT_PROPERTIES || variable in WRONG_ON_PURPOSE || variable.startsWith(HARNESS) ||
            KubernetesServiceLinks.isServiceLink(variable, "tcp://10.96.12.34:80")

    @Test
    fun `every COSEC variable the repository documents or ships is a property the starter reads`() {
        val files = scannedFiles()
        files.size.assert().describedAs("files scanned").isGreaterThan(100)
        val texts = files.associateWith { it.readText() }
        val seen = texts.flatMap { (file, text) -> variables(file, text) }.toSet()
        seen.assert().describedAs("the walk finds the variables that are known to be there")
            .contains("COSEC_JWT_SECRET", "COSEC_CONTROL_ENABLED", "COSEC_PIPELINE_IDENTITY_ONLYGATEWAYCANREACH")
        problems(texts).assert().isEmpty()
    }

    @Test
    fun `a variable written with an underscore inside a word would fail the scan, and names the spelling to write`() {
        val file = repository.resolve("wiki/operations/deployment.md")
        val written = mapOf(
            file to "set `COSEC_AUTHORIZATION_SNAPSHOT_EVICTION_CHECK=warn` and `COSEC_JWT_SESSION_MEMBERSHIPS_ON_REFRESH=from-token`",
        )
        problems(written).assert().containsExactly(
            "wiki/operations/deployment.md: COSEC_AUTHORIZATION_SNAPSHOT_EVICTION_CHECK is read as " +
                "cosec.authorization.snapshot.eviction.check, which no property is (write " +
                "cosec.authorization.snapshot.eviction-check, or the variable COSEC_AUTHORIZATION_SNAPSHOT_EVICTIONCHECK)",
            "wiki/operations/deployment.md: COSEC_JWT_SESSION_MEMBERSHIPS_ON_REFRESH is read as " +
                "cosec.jwt.session.memberships.on.refresh, which no property is (write " +
                "cosec.jwt.session.memberships-on-refresh, or the variable COSEC_JWT_SESSION_MEMBERSHIPSONREFRESH)",
        )
    }

    @Test
    fun `the spellings the pages show as wrong are wrong`() {
        for (variable in WRONG_ON_PURPOSE) {
            refused(variable).assert().describedAs(variable).isNotEmpty()
        }
        val texts = scannedFiles().associateWith { it.readText() }
        val seen = texts.flatMap { (file, text) -> variables(file, text) }.toSet()
        seen.assert().describedAs(
            "a wrong spelling nothing shows any more is dropped from the list"
        ).containsAll(WRONG_ON_PURPOSE)
    }

    private companion object {
        val VARIABLE = Regex("(?<![A-Za-z0-9_])COSEC_[A-Z0-9]+(?:_[A-Z0-9]+)*")
        val LITERAL = Regex("\"(?:[^\"\\\\\\n]|\\\\.)*\"")
        val EXTENSIONS = setOf("md", "yaml", "yml", "sh", "kt", "txt")
        val SKIPPED_DIRECTORIES = setOf("build", "node_modules", ".vitepress", ".gradle", "dist")

        /** The harness of `test/system-test` and its compose file: `COSEC_SYSTEM_*` are its own, never the host's. */
        const val HARNESS = "COSEC_SYSTEM"

        /** Named in the pages, and no environment variable: constants of the 7.x API the upgrade pages list. */
        val NOT_PROPERTIES = setOf("COSEC_PREFIX", "COSEC_EXTEND_KEY_PREFIX")

        /** The mistakes the configuration page and the troubleshooting page show, each refused by the check. */
        val WRONG_ON_PURPOSE = setOf(
            "COSEC_JWT_SECRT",
            "COSEC_PIPELINE_TIMEUOT",
            "COSEC_AUTHORIZATION_SNAPSHOT_LOCAL_MAX_AGE",
        )
    }
}
