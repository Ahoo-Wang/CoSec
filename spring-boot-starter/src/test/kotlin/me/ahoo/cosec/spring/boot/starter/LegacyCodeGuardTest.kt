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

package me.ahoo.cosec.spring.boot.starter

import com.tngtech.archunit.core.importer.ClassFileImporter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

/** G1 catches what Kotlin writes for `@Deprecated`, and only that. */
class LegacyCodeGuardTest {
    private class DeprecatedProperty {
        @Deprecated("replaced")
        val old: String = ""
    }

    private class JavaDeprecatedMethod(private val value: String) {
        @java.lang.Deprecated
        fun old(): String = value
    }

    private class CompanionConstant {
        fun value(): Int = SIZE

        companion object {
            private const val SIZE = 1
        }
    }

    private interface Defaulted {
        fun value(): Int = 1
    }

    /** The `DefaultImpls` bridges of [Defaulted], where the compiler generates them. */
    private fun defaultImpls(): Array<Class<*>> =
        runCatching {
            arrayOf<Class<*>>(
                Class.forName(Defaulted::class.java.name + "\$DefaultImpls")
            )
        }.getOrDefault(emptyArray())

    private fun classesOf(vararg types: Class<*>) = ClassFileImporter().importClasses(*types)

    @Test
    fun `a deprecated property is caught`() {
        assertThrows<AssertionError> { Guards.noDeprecatedMember.check(classesOf(DeprecatedProperty::class.java)) }
    }

    @Test
    fun `a java deprecated method is caught`() {
        assertThrows<AssertionError> { Guards.noDeprecatedMember.check(classesOf(JavaDeprecatedMethod::class.java)) }
    }

    @Test
    fun `members the compiler marks are not`() {
        assertDoesNotThrow {
            Guards.noDeprecatedMember.check(
                classesOf(CompanionConstant::class.java, CompanionConstant.Companion::class.java, *defaultImpls())
            )
        }
    }
}
