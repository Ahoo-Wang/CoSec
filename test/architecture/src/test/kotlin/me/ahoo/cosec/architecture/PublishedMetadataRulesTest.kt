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

/**
 * [PublishedMetadataRules] against wrong metadata: a rule that always answers "no violation" would pass
 * [PublishedMetadataTest] too, so each rule has a sample it must refuse, and a sample it must accept.
 */
class PublishedMetadataRulesTest {
    private fun pom(dependencies: String = "", management: String = ""): String =
        """<project>${if (management.isNotEmpty()) "<dependencyManagement><dependencies>$management</dependencies></dependencyManagement>" else ""}
           <dependencies>$dependencies</dependencies></project>"""

    private fun dependency(id: String, version: String? = "1.0", scope: String? = "compile", extra: String = ""): String {
        val (group, name) = id.split(':')
        return "<dependency><groupId>$group</groupId><artifactId>$name</artifactId>" +
            (version?.let { "<version>$it</version>" } ?: "") + (scope?.let { "<scope>$it</scope>" } ?: "") + extra +
            "</dependency>"
    }

    private fun gradle(api: String = "", runtime: String = "", constraints: String = ""): String =
        """{"variants":[{"name":"apiElements","dependencies":[$api],"dependencyConstraints":[$constraints]},
            {"name":"runtimeElements","dependencies":[$runtime]}]}"""

    private fun module(id: String, version: String? = "1.0", attributes: String = ""): String {
        val (group, name) = id.split(':')
        return """{"group":"$group","module":"$name"""" +
            (version?.let { ""","version":{"requires":"$it"}""" } ?: "") +
            (if (attributes.isNotEmpty()) ""","attributes":{$attributes}""" else "") + "}"
    }

    private fun parse(pom: String, module: String) = PublishedMetadata.parse("cosec-sample", pom, module)

    private val clean = parse(
        pom(dependency("me.ahoo.cosec:cosec-runtime") + dependency("org.slf4j:slf4j-api", scope = "runtime")),
        gradle(
            api = module("me.ahoo.cosec:cosec-runtime"),
            runtime = module("me.ahoo.cosec:cosec-runtime") + "," + module("org.slf4j:slf4j-api"),
        ),
    )

    @Test
    fun `clean metadata has no violation under any rule`() {
        listOf(
            PublishedMetadataRules::noPlatform,
            PublishedMetadataRules::noInternalPlatform,
            PublishedMetadataRules::everyDependencyHasAVersion,
            PublishedMetadataRules::noTools,
            PublishedMetadataRules::pomBringsNoMoreThanRuntimeElements,
        ).flatMap { it(clean) }.assert().isEmpty()
    }

    @Test
    fun `a POM that imports a platform is refused`() {
        val metadata = parse(
            pom(
                management = dependency(
                    "me.ahoo.cosec:cosec-dependencies",
                    scope = "import",
                    extra = "<type>pom</type>"
                )
            ),
            gradle(),
        )
        PublishedMetadataRules.noPlatform(metadata).assert().hasSize(1)
        PublishedMetadataRules.noInternalPlatform(metadata).assert().hasSize(1)
    }

    @Test
    fun `a Gradle dependency on a platform is refused, in the variant that has it`() {
        val platform =
            module(
                "org.springframework.boot:spring-boot-dependencies",
                attributes = """"org.gradle.category":"platform""""
            )
        PublishedMetadataRules.noPlatform(parse(pom(), gradle(runtime = platform)))
            .assert().containsExactly(
                "cosec-sample: variant runtimeElements depends on the platform org.springframework.boot:spring-boot-dependencies"
            )
        PublishedMetadataRules.noPlatform(
            parse(pom(), gradle(api = module("a:b", attributes = """"org.gradle.category":"enforced-platform"""")))
        ).assert().hasSize(1)
    }

    @Test
    fun `a dependency without a version is refused in the POM and in the Gradle metadata`() {
        val metadata = parse(
            pom(dependency("org.slf4j:slf4j-api", version = null)),
            gradle(runtime = module("org.slf4j:slf4j-api", version = null)),
        )
        PublishedMetadataRules.everyDependencyHasAVersion(metadata).assert().hasSize(2)
    }

    @Test
    fun `a test or build tool is refused wherever it is named`() {
        TOOLS_SAMPLES.forEach { id ->
            PublishedMetadataRules.noTools(parse(pom(dependency(id)), gradle())).assert().hasSize(1)
            PublishedMetadataRules.noTools(parse(pom(), gradle(runtime = module(id)))).assert().hasSize(1)
            PublishedMetadataRules.noTools(parse(pom(management = dependency(id)), gradle())).assert().hasSize(1)
        }
        PublishedMetadataRules.noTools(
            parse(pom(dependency("org.junit.jupiter:junit-jupiter-api")), gradle())
        ).assert().isEmpty()
    }

    @Test
    fun `a dependency only the API variant has is refused as compile scope, and accepted as provided or optional`() {
        val api = gradle(api = module("me.ahoo.cosec:cosec-control"))
        PublishedMetadataRules.pomBringsNoMoreThanRuntimeElements(
            parse(pom(dependency("me.ahoo.cosec:cosec-control")), api)
        )
            .assert().hasSize(1)
        PublishedMetadataRules.pomBringsNoMoreThanRuntimeElements(
            parse(pom(dependency("me.ahoo.cosec:cosec-control", scope = "runtime")), api)
        )
            .assert().hasSize(1)
        PublishedMetadataRules.pomBringsNoMoreThanRuntimeElements(
            parse(pom(dependency("me.ahoo.cosec:cosec-control", scope = "provided")), api)
        )
            .assert().isEmpty()
        PublishedMetadataRules.pomBringsNoMoreThanRuntimeElements(
            parse(pom(dependency("me.ahoo.cosec:cosec-control", extra = "<optional>true</optional>")), api)
        ).assert().isEmpty()
    }

    @Test
    fun `the run-time closure follows compile and runtime scope, not provided or optional`() {
        fun published(vararg entries: Pair<String, String>) = entries.associate { (artifact, dependencies) ->
            artifact to PublishedMetadata.parse(artifact, pom(dependencies), gradle())
        }
        val chain = published(
            "cosec-a" to dependency("me.ahoo.cosec:cosec-b"),
            "cosec-b" to dependency("me.ahoo.cosec:cosec-runtime", scope = "runtime"),
        )
        PublishedMetadataRules.runtimeClosure("cosec-a", chain).assert().containsExactly("cosec-b", "cosec-runtime")
        val safe = published(
            "cosec-a" to dependency("me.ahoo.cosec:cosec-b", scope = "provided") +
                dependency("me.ahoo.cosec:cosec-c", extra = "<optional>true</optional>"),
        )
        PublishedMetadataRules.runtimeClosure("cosec-a", safe).assert().isEmpty()
    }

    @Test
    fun `a BOM must list the dependencies exactly, and constrain only CoSec modules`() {
        val expected = setOf("cosec-a", "cosec-b")
        fun bom(vararg ids: String) = parse(pom(management = ids.joinToString("") { dependency(it) }), gradle())
        PublishedMetadataRules.bomListsExactly(
            bom("me.ahoo.cosec:cosec-a", "me.ahoo.cosec:cosec-b"),
            expected
        ).assert().isEmpty()
        PublishedMetadataRules.bomListsExactly(bom("me.ahoo.cosec:cosec-a"), expected)
            .assert().containsExactly("cosec-sample: does not list cosec-b")
        PublishedMetadataRules.bomListsExactly(
            bom("me.ahoo.cosec:cosec-a", "me.ahoo.cosec:cosec-b", "me.ahoo.cosec:cosec-migrate"),
            expected
        )
            .assert().containsExactly("cosec-sample: lists cosec-migrate, which is not a dependency")
        PublishedMetadataRules.bomListsExactly(
            bom("me.ahoo.cosec:cosec-a", "me.ahoo.cosec:cosec-b", "org.springframework.boot:spring-boot-dependencies"),
            expected
        )
            .assert().hasSize(1)
    }

    private companion object {
        val TOOLS_SAMPLES = listOf(
            "org.openjdk.jmh:jmh-core",
            "io.mockk:mockk",
            "com.tngtech.archunit:archunit-junit5",
            "io.projectreactor.tools:blockhound",
            "io.gitlab.arturbosch.detekt:detekt-formatting",
            "com.networknt:json-schema-validator",
            "me.ahoo.test:fluent-assert-core",
        )
    }
}
