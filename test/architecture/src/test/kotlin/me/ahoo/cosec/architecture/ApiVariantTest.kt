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

        /**
         * Reviewed exceptions: a package whose public signatures may name these libraries though its module's API
         * variant does not carry them. Only the test kit's web bindings (`PIP-29`): whoever calls them tests a handler of
         * that stack and has it on the test classpath through its own main dependencies, while `compileOnlyApi` would put
         * both stacks on every build that uses the kit. `SourceRulesTest` keeps the rest of the kit off a web stack.
         */
        val NOT_EXPORTED: Map<String, Set<String>> = mapOf(
            "me.ahoo.cosec.testkit.webflux" to setOf("org.springframework:spring-web", "io.projectreactor:reactor-core"),
            "me.ahoo.cosec.testkit.webmvc" to setOf("jakarta.servlet:jakarta.servlet-api"),
        )
        val JACKSON = Regex("""tools\.jackson\.""")

        /**
         * The library modules, but the starter (its optional technologies are `compileOnly`, so its classes do not load
         * here) and `migrate` (a command line, whose contract is not an API).
         */
        val LIBRARIES: Set<String> = ProjectListTest.GROUPS.filterKeys { it !in setOf("bom", "platform") }.values
            .flatten()
            .filterNot {
                it in setOf("conformance", "architecture", "e2e", "client-host", "system-test", "system-behind", "system-auth", "system-enforcer", "code-coverage-report", "migrate", "spring-boot")
            }
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

    /**
     * Any library a public signature names, not only Jackson: a consumer that adds the module alone cannot compile a call
     * to an entry whose signature names a type its API variant does not carry (#1389). What the module's own class path
     * holds, being the implementation's, says nothing about that.
     */
    @Test
    fun `the API variant carries every library a public signature names`() {
        val seen = LIBRARIES.associateWith { project ->
            PublicSignatures.libraryTypes(
                PublicSignatures.classes {
                    it == project
                }
            )
        }

        // A type the rule cannot place is one it cannot vouch for: fail on it, naming it, instead of skipping it.
        val unplaced = seen.flatMap { (project, types) ->
            types.filter { it.library == null }.map { "$project: ${it.where} names ${it.type}, whose library cannot be told" }
        }
        unplaced.assert().isEmpty()

        // And it must have looked: these libraries are in these modules' public signatures for certain, so a run that
        // found none of them found nothing at all (another cache layout, a type ArchUnit did not resolve).
        // MetricsListener takes a MeterRegistry; the test kit's `tests` returns JUnit's DynamicTest, in its API by design.
        seen.getValue("micrometer").map { it.library }.assert().contains("io.micrometer:micrometer-core")
        seen.getValue("testkit").map { it.library }.assert().contains("org.junit.jupiter:junit-jupiter-api")

        val excused = HashSet<Pair<String, String>>()
        val missing = seen.flatMap { (project, types) ->
            val exported = facts(project, "module").map(::jar).toSet()
            types.filter { jar(it.library!!) !in exported }
                .filterNot { type ->
                    val entry = NOT_EXPORTED.entries.firstOrNull { (pkg, libraries) ->
                        declaredIn(type.where) == pkg && type.library in libraries
                    }
                    entry?.let { excused += it.key to type.library!! } != null
                }
                .map { "$project: ${it.where} names ${it.type}, in ${it.library}, which its API variant does not carry" }
        }
        missing.assert().isEmpty()
        // An exception that excuses nothing is stale: it must go, so the list cannot outlive what it was written for.
        NOT_EXPORTED.flatMap { (pkg, libraries) -> libraries.map { pkg to it } }.filter { it !in excused }.assert().isEmpty()
    }

    /** The package of the class that declares [where] (`pkg.Class.member(...)`). */
    private fun declaredIn(where: String): String = where.substringBefore('(').substringBeforeLast('.').substringBeforeLast('.')

    /** `kotlinx-coroutines-core` is `-jvm` in the jar the class path holds and the variant names the other. */
    private fun jar(module: String) = module.removeSuffix("-jvm")

    /** The core and the contracts carry no Jackson (`JacksonSignatureTest`); a module that does must export it. */
    @Test
    fun `the API variant carries Jackson where a public signature names it`() {
        val named = LIBRARIES.filter { project ->
            PublicSignatures.violations(PublicSignatures.classes { it == project }, JACKSON).isNotEmpty()
        }
        // Since round 2 L9 none does: the test kit, which this rule was written for, takes scenarios as text. One that
        // names Jackson again must export it.
        named.filter { JACKSON_DATABIND !in facts(it, "module") }.assert().isEmpty()
    }
}
