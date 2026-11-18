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

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * SNP-12 across every module, from the compiled classes: only `SnapshotDocuments` makes a `SnapshotDocument`, by reading
 * its header from its body (`read`) or from the document it encodes (`of`). The constructor is `@InternalCoSecApi`, so
 * another CoSec module could call it by opting in and state a header its body contradicts: an instance would then report
 * a revision it does not enforce. A caller that cannot go through the factory is named and measured here, never
 * allowlisted silently.
 */
class SnapshotDocumentMakersTest {
    private val classes = ClassFileImporter().withImportOption(DoNotIncludeTests()).importPackages("me.ahoo.cosec")

    @Test
    fun `only SnapshotDocuments makes a snapshot document`() {
        val calls = classes.flatMap { it.constructorCallsFromSelf }.filter { it.targetOwner.name == DOCUMENT }
        // The rule sees what it guards: the factory makes them.
        calls.map { it.originOwner.name }.toSet().assert().describedAs("who makes a snapshot document")
            .contains(FACTORY)
        calls.filterNot { MAKER.matches(it.originOwner.name) }
            .map { "${it.origin.fullName} makes a SnapshotDocument" }
            .assert()
            .describedAs("snapshot documents made outside SnapshotDocuments (SNP-12)")
            .isEmpty()
    }

    private companion object {
        const val DOCUMENT = "me.ahoo.cosec.spi.snapshot.SnapshotDocument"
        const val FACTORY = "me.ahoo.cosec.document.SnapshotDocuments"

        /** The factory and the classes Kotlin compiles its lambdas and nested classes into. */
        val MAKER = Regex("""me\.ahoo\.cosec\.document\.SnapshotDocuments(\$.*)?""")
    }
}
