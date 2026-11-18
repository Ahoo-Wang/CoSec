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
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * What every published module tells an application's build, read from the POM and the Gradle module metadata the build
 * generates for publication (`cosec.published`, `artifact=pom|module;…`). Adding `cosec-login` to a build must change
 * nothing about its Spring Boot or CoCache beyond what `cosec-login` needs, so no module imports a platform, and an
 * application's Maven build resolves the same run-time classpath Gradle does (R11 entries 6 and 20). The rules are
 * [PublishedMetadataRules]; [PublishedMetadataRulesTest] holds them to wrong samples.
 */
class PublishedMetadataTest {
    private companion object {
        val published: Map<String, PublishedMetadata> = requireNotNull(System.getProperty("cosec.published")) {
            "cosec.published is not set: run the rules through Gradle."
        }.split(';').filter { it.isNotBlank() }.associate { entry ->
            val (artifact, files) = entry.split('=', limit = 2)
            val (pom, module) = files.split('|', limit = 2)
            artifact to PublishedMetadata.parse(artifact, Path.of(pom).readText(), Path.of(module).readText())
        }

        /** The modules an application can depend on: every library project but `migrate`, a command-line tool. */
        val DEPENDENCIES: Set<String> = requireNotNull(System.getProperty("cosec.coverage.projects"))
            .split(';').filter { it.isNotBlank() && it != "migrate" }.map { "cosec-$it" }.toSet()

        const val BOM = "cosec-bom"
    }

    private fun libraries() = published.filterKeys { it != BOM }.values

    @Test
    fun `every library module and the BOM is read`() {
        published.keys.assert().containsAll(DEPENDENCIES + BOM)
        published.keys.minus(DEPENDENCIES + BOM).assert().containsExactly("cosec-migrate")
    }

    @Test
    fun `no published module imports or depends on a platform`() {
        published.values.flatMap(PublishedMetadataRules::noPlatform).assert().isEmpty()
    }

    @Test
    fun `no published module names the build's own platform`() {
        published.values.flatMap(PublishedMetadataRules::noInternalPlatform).assert().isEmpty()
    }

    @Test
    fun `every dependency a library publishes has a version`() {
        libraries().flatMap(PublishedMetadataRules::everyDependencyHasAVersion).assert().isEmpty()
    }

    @Test
    fun `no published module names a test or build tool`() {
        published.values.flatMap(PublishedMetadataRules::noTools).assert().isEmpty()
    }

    @Test
    fun `a POM brings at run time what the Gradle run-time variant brings, no more`() {
        libraries().flatMap(PublishedMetadataRules::pomBringsNoMoreThanRuntimeElements).assert().isEmpty()
    }

    /** `compileOnlyApi` is the case: Gradle's API variant has it, its run-time variant does not, and so must the POM. */
    @Test
    fun `a compile-only API dependency is provided in the POM`() {
        val control = published.getValue("cosec-cocache").pomDependencies.single { it.name == "cosec-control" }
        control.scope.assert().isEqualTo("provided")
        published.getValue("cosec-kafka").pomDependencies.single { it.name == "cosec-control" }
            .scope.assert().isEqualTo("provided")
    }

    /** RFC 0013 §3, as an application's Maven build sees it: a Redis store does not bring the request path. */
    @Test
    fun `the POM of a backend or a document tool brings no cosec-runtime`() {
        val projects = (DependencyRules.DOCUMENT_ONLY + ProjectListTest.GROUPS.getValue(DependencyRules.BACKENDS))
            .map { "cosec-$it" }
        projects.assert().isNotEmpty()
        projects.filter { "cosec-runtime" in PublishedMetadataRules.runtimeClosure(it, published) }.assert().isEmpty()
    }

    @Test
    fun `the BOM lists the CoSec modules that are dependencies, and nothing else`() {
        PublishedMetadataRules.bomListsExactly(published.getValue(BOM), DEPENDENCIES).assert().isEmpty()
    }
}
