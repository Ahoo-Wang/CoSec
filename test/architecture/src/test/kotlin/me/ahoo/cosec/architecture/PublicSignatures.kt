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
import com.tngtech.archunit.core.domain.JavaField
import com.tngtech.archunit.core.domain.JavaMember
import com.tngtech.archunit.core.domain.JavaModifier
import java.lang.reflect.Method
import kotlin.reflect.KVisibility
import kotlin.reflect.jvm.kotlinFunction

/**
 * The public signatures of a project's main classes, as a consumer compiles against them. Kotlin `internal` compiles to
 * public bytecode, so visibility comes from the Kotlin metadata; `@InternalCoSecApi` is kept in the class files only,
 * so it comes from ArchUnit, and a member or class carrying it is not public API.
 */
object PublicSignatures {
    private const val INTERNAL_API = "me.ahoo.cosec.annotations.InternalCoSecApi"

    /** The public classes of the projects [inProject] accepts. */
    fun classes(inProject: (String) -> Boolean): List<JavaClass> = SourceRulesTest.CLASSES
        .filter { javaClass -> SourceRulesTest.projectOf(javaClass)?.let(inProject) == true }
        .filter(::isPublicKotlin)

    /** Each public supertype and member signature of [classes] that names a type matching [type]. */
    fun violations(classes: List<JavaClass>, type: Regex): List<String> = classes.flatMap { javaClass ->
        val supertypes = (listOfNotNull(javaClass.rawSuperclass.orElse(null)) + javaClass.rawInterfaces)
            .filter { type.containsMatchIn(it.name) }
            .map { "${javaClass.name} extends ${it.name}" }
        val members = (javaClass.methods + javaClass.constructors + javaClass.fields)
            .filter(::isPublic)
            .map(::signature)
            .filter { type.containsMatchIn(it) }
        supertypes + members
    }

    private fun isPublicKotlin(javaClass: JavaClass): Boolean {
        if (javaClass.isAnonymousClass || javaClass.modifiers.contains(JavaModifier.SYNTHETIC)) return false
        if (!javaClass.modifiers.contains(JavaModifier.PUBLIC)) return false
        if (javaClass.isAnnotatedWith(INTERNAL_API)) return false
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
        if (member.isAnnotatedWith(INTERNAL_API)) return false
        // Kotlin mangles the names of internal functions and accessors with the module name.
        if ('$' in member.name) return false
        val method = (member as? JavaCodeUnit)?.let { runCatching { it.reflect() }.getOrNull() } as? Method
        val visibility = method?.kotlinFunction?.visibility
        return visibility == null || visibility == KVisibility.PUBLIC || visibility == KVisibility.PROTECTED
    }

    private fun signature(member: JavaMember): String = when (member) {
        is JavaCodeUnit -> runCatching { (member.reflect() as? Method)?.toGenericString() }.getOrNull()
            ?: (member.fullName + member.rawReturnType.name)
        else -> member.fullName + (member as? JavaField)?.rawType?.name
    }
}
