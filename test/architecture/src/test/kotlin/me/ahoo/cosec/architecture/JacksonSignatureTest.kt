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
import com.tngtech.archunit.core.domain.JavaCodeUnit
import com.tngtech.archunit.core.domain.JavaMember
import com.tngtech.archunit.core.domain.JavaModifier
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import kotlin.reflect.KVisibility
import kotlin.reflect.jvm.kotlinFunction

/**
 * RFC 0011 D3: the public signatures of the contracts and the core carry no Jackson type, so a Jackson major upgrade is
 * never a breaking change of CoSec. A member CoSec's own modules need marks itself `@InternalCoSecApi` (RFC 0011 D4).
 *
 * Kotlin `internal` compiles to public bytecode, so visibility comes from the Kotlin metadata; `@InternalCoSecApi` is
 * kept in the class files only, so it comes from ArchUnit.
 */
class JacksonSignatureTest {
    private val inner = setOf("api", "core")
    private val internalApi = "me.ahoo.cosec.annotations.InternalCoSecApi"

    private fun isPublicKotlin(javaClass: JavaClass): Boolean {
        if (javaClass.isAnonymousClass || javaClass.modifiers.contains(JavaModifier.SYNTHETIC)) return false
        if (!javaClass.modifiers.contains(JavaModifier.PUBLIC)) return false
        if (javaClass.isAnnotatedWith(internalApi)) return false
        javaClass.enclosingClass.orElse(null)?.let { if (!isPublicKotlin(it)) return false }
        val type = javaClass.reflect()
        val metadata = type.getAnnotation(Metadata::class.java) ?: return true
        if (metadata.kind != 1) return true
        return try {
            type.kotlin.visibility.let { it == KVisibility.PUBLIC || it == KVisibility.PROTECTED }
        } catch (_: UnsupportedOperationException) {
            false
        }
    }

    private fun isPublic(member: JavaMember): Boolean {
        val modifiers = member.modifiers
        if (JavaModifier.SYNTHETIC in modifiers || JavaModifier.BRIDGE in modifiers) return false
        if (JavaModifier.PUBLIC !in modifiers && JavaModifier.PROTECTED !in modifiers) return false
        if (member.isAnnotatedWith(internalApi)) return false
        // Kotlin mangles the names of internal functions and accessors with the module name.
        if ('$' in member.name) return false
        val method = (member as? JavaCodeUnit)?.let { runCatching { it.reflect() }.getOrNull() } as? Method
        val visibility = method?.kotlinFunction?.visibility
        return visibility == null || visibility == KVisibility.PUBLIC || visibility == KVisibility.PROTECTED
    }

    private fun signature(member: JavaMember): String = when (member) {
        is JavaCodeUnit -> runCatching { (member.reflect() as? Method)?.toGenericString() }.getOrNull()
            ?: member.fullName + member.rawReturnType.name
        else -> member.fullName + (member as? com.tngtech.archunit.core.domain.JavaField)?.rawType?.name
    }

    @Test
    fun `the contracts and the core carry no Jackson type in a public signature`() {
        val checked = SourceRulesTest.CLASSES
            .filter { javaClass -> SourceRulesTest.projectOf(javaClass)?.let { Projects.groupOf(it) in inner } == true }
            .filter(::isPublicKotlin)
        // The rule sees the codec: a filter that let nothing through would pass vacuously.
        checked.map { it.name }.assert().contains("me.ahoo.cosec.runtime.document.PolicyDocumentCodec")
        val violations = checked
            .flatMap { javaClass ->
                val supertypes = (listOfNotNull(javaClass.rawSuperclass.orElse(null)) + javaClass.rawInterfaces)
                    .filter { it.name.startsWith("tools.jackson.") }
                    .map { "${javaClass.name} extends ${it.name}" }
                val members = (javaClass.methods + javaClass.constructors + javaClass.fields)
                    .filter(::isPublic)
                    .map(::signature)
                    .filter { "tools.jackson." in it }
                supertypes + members
            }
        violations.assert().isEmpty()
    }
}
