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
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * What an application that compiles against a library module gets from its Gradle API variant (usage `java-api`, the
 * root build's `architectureApiConsumer`): a type in a public signature that the variant does not carry cannot be
 * resolved by javac or kotlinc, so even a call that does not use that type fails to compile (#1261, #1276). The
 * module's own tests see its implementation classpath, which hides it.
 */
class ApiVariantTest {
    private companion object {
        const val JACKSON_DATABIND = "tools.jackson.core:jackson-databind"
        val JACKSON = Regex("""tools\.jackson\.""")

        /**
         * The library modules, but the starter (its optional technologies are `compileOnly`, so its classes do not load
         * here) and `migrate` (a command line, whose contract is not an API).
         */
        val LIBRARIES: Set<String> = ProjectListTest.GROUPS.filterKeys { it !in setOf("bom", "platform") }.values
            .flatten()
            .filterNot { it in setOf("conformance", "architecture", "e2e", "code-coverage-report", "migrate", "spring-boot") }
            .toSet()
    }

    private fun facts(project: String, kind: String): List<String> = Projects.facts[project].orEmpty()
        .map { it.split(' ') }
        .filter { it[0] == "resolved" && it[1] == "apiElements" && it[2] == kind }
        .map { it[3] }

    private fun supertypes(javaClass: JavaClass): List<JavaClass> =
        listOfNotNull(javaClass.rawSuperclass.orElse(null)) + javaClass.rawInterfaces

    @Test
    fun `every library module has its API variant resolved`() {
        LIBRARIES.filter { project -> project !in facts(project, "project") }.assert().isEmpty()
    }

    /** A public class extends or implements a type of another CoSec module: javac needs it to use the class at all. */
    @Test
    fun `the API variant carries the CoSec modules whose types a public class extends or implements`() {
        val checked = LIBRARIES.flatMap { project -> PublicSignatures.classes { it == project } }
        checked.map { it.name }.assert().contains("me.ahoo.cosec.gateway.PipelineGatewayFilter")
        val violations = checked.flatMap { javaClass ->
            val project = SourceRulesTest.projectOf(javaClass) ?: return@flatMap emptyList()
            val exported = facts(project, "project")
            supertypes(javaClass)
                .mapNotNull { supertype -> SourceRulesTest.projectOf(supertype)?.let { it to supertype.name } }
                .filter { (other, _) -> other != project && other !in exported }
                .map { (other, supertype) -> "$project: ${javaClass.name} extends $supertype of $other" }
        }
        violations.assert().isEmpty()
    }

    /** The core and the contracts carry no Jackson (`JacksonSignatureTest`); a module that does must export it. */
    @Test
    fun `the API variant carries Jackson where a public signature names it`() {
        val named = LIBRARIES.filter { project ->
            PublicSignatures.violations(PublicSignatures.classes { it == project }, JACKSON).isNotEmpty()
        }
        // The rule sees the policy test kit, whose scenario entry points take a JsonNode.
        named.assert().contains("testkit")
        named.filter { JACKSON_DATABIND !in facts(it, "module") }.assert().isEmpty()
    }
}
