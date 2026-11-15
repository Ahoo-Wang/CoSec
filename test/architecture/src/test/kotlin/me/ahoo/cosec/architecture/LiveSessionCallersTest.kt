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

import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * IDN-14, IDN-22 across every module, from the compiled classes: only `cosec-login`'s live-session check moves a token
 * family on (`rotate`), starts one at an earlier login (`startAt`) or asks whether one is live (`requireLive`). They are
 * `@InternalCoSecApi`, so another CoSec module (`cosec-jwt`, `cosec-social`, the starter) could call them by opting in,
 * and issue from an earlier token without the strict revocation read and the family check. Within `cosec-login`,
 * `LiveSessionsGuardTest` keeps every authentication that reads an earlier credential holding the check.
 */
class LiveSessionCallersTest {
    private val classes = ClassFileImporter().withImportOption(DoNotIncludeTests()).importPackages("me.ahoo.cosec")

    @Test
    fun `only the live-session check rotates, forks or checks a token family`() {
        val calls = classes.flatMap { it.methodCallsFromSelf }.filter(::issuing)
        // The rule sees what it guards: every one of the three is called, by the check.
        val called = calls.map(::method).toSet()
        called.assert().describedAs("the family methods called").containsExactlyInAnyOrder(*METHODS)
        val outside = calls.filterNot { CHECK.matches(it.originOwner.name) }
            .map { "${it.origin.fullName} calls ${method(it)}" }
        outside.assert()
            .describedAs("calls of rotate, startAt or requireLive outside the live-session check (IDN-22)")
            .isEmpty()
    }

    private fun issuing(call: JavaMethodCall): Boolean =
        call.targetOwner.name == FAMILIES && method(call) in METHODS && !OWN.matches(call.originOwner.name)

    /** A function's name without the suffix Kotlin mangles it with when it takes a value class (`startAt-hPxUw84`). */
    private fun method(call: JavaMethodCall): String = call.target.name.substringBefore('-')

    private companion object {
        const val FAMILIES = "me.ahoo.cosec.login.token.TokenFamilies"

        /** The class itself and its coroutine continuations, through which a suspend function calls itself back. */
        val OWN = Regex("""me\.ahoo\.cosec\.login\.token\.TokenFamilies(\$.*)?""")
        val CHECK = Regex("""me\.ahoo\.cosec\.login\.token\.LiveSessions(\$.*)?""")
        val METHODS = arrayOf("rotate", "startAt", "requireLive")
    }
}
