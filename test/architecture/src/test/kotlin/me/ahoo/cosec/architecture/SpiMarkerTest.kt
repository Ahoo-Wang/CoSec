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
import kotlin.io.path.readLines
import kotlin.reflect.KVisibility

/**
 * RFC 0011 D4: an interface applications implement says so with `@CoSecSpi`. A new port in `cosec-spi`, or a new row in
 * the extension points of `wiki/architecture/extending.md`, fails here until its interface carries the marker.
 */
class SpiMarkerTest {
    companion object {
        private const val SPI = "me.ahoo.cosec.annotations.CoSecSpi"
        private const val EXTENDING = "wiki/architecture/extending.md"

        /** Public interfaces of `cosec-spi` that CoSec implements and applications only call, with the reason. */
        val CALLED_NOT_IMPLEMENTED: Map<String, String> = mapOf(
            "me.ahoo.cosec.spi.snapshot.SnapshotChangeListener" to
                "SnapshotSource implements it; a SnapshotDocumentStore only calls the listener it is given",
        )

        /** Whether code outside the declaring module can implement [javaClass]: an interface that is not sealed. */
        fun implementable(javaClass: JavaClass): Boolean =
            javaClass.isInterface && !javaClass.isAnnotation && !javaClass.reflect().kotlin.isSealed

        /**
         * The types the extension points table of extending.md names, as `me.ahoo.cosec.<package>.<Type>`: the first
         * column holds the types, the second the module and the package. A row without a type (route maps) names none.
         */
        fun extensionPoints(): List<String> {
            val rows = Projects.root.resolve(EXTENDING).readLines()
                .dropWhile { it != "## Extension points" }
                .dropWhile { !it.startsWith("|") }
                .takeWhile { it.startsWith("|") }
                .drop(2)
            val code = Regex("`([^`]+)`")
            return rows.flatMap { row ->
                val cells = row.split('|').drop(1)
                val types = code.findAll(cells[0]).map { it.groupValues[1] }.toList()
                val location = code.findAll(cells[1]).map { it.groupValues[1] }.toList()
                types.map { type -> "me.ahoo.cosec.${location[1]}.$type" }
            }
        }
    }

    @Test
    fun `every interface of cosec-spi an application can implement carries CoSecSpi`() {
        val interfaces = PublicSignatures.classes { it == "spi" }.filter(::implementable)
        // A filter that let nothing through would pass vacuously.
        interfaces.map { it.name }.assert().contains("me.ahoo.cosec.spi.membership.MembershipProvider")
        interfaces.filterNot { it.isAnnotatedWith(SPI) }.map { it.name }
            .filterNot { it in CALLED_NOT_IMPLEMENTED }
            .assert().isEmpty()
    }

    @Test
    fun `each exception is an unmarked interface of cosec-spi`() {
        CALLED_NOT_IMPLEMENTED.keys.filterNot { name ->
            SourceRulesTest.CLASSES.contain(name) && SourceRulesTest.CLASSES.get(name).let {
                SourceRulesTest.projectOf(it) == "spi" && implementable(it) && !it.isAnnotatedWith(SPI)
            }
        }.assert().isEmpty()
    }

    @Test
    fun `every extension point extending md lists carries CoSecSpi`() {
        val listed = extensionPoints()
        listed.assert().contains(
            "me.ahoo.cosec.runtime.event.DecisionListener",
            "me.ahoo.cosec.spi.authn.Authenticator"
        )
        listed.filterNot { name ->
            SourceRulesTest.CLASSES.contain(name) && SourceRulesTest.CLASSES.get(name).isAnnotatedWith(SPI)
        }.assert().describedAs("extension points in $EXTENDING without @CoSecSpi, or not found").isEmpty()
    }

    @Test
    fun `CoSecSpi marks only public interfaces an application can implement`() {
        val marked = SourceRulesTest.CLASSES.filter { it.isAnnotatedWith(SPI) }
        marked.map { it.name }.assert().contains("me.ahoo.cosec.runtime.event.DecisionListener")
        marked.filterNot { implementable(it) && it.reflect().kotlin.visibility == KVisibility.PUBLIC }
            .map { it.name }
            .assert().isEmpty()
    }
}
