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
import com.tngtech.archunit.core.domain.JavaType
import java.lang.reflect.Executable
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
    private const val EXPERIMENTAL_API = "me.ahoo.cosec.annotations.ExperimentalCoSecApi"

    /** The public classes of the projects [inProject] accepts. */
    fun classes(inProject: (String) -> Boolean): List<JavaClass> = SourceRulesTest.CLASSES
        .filter { javaClass -> SourceRulesTest.projectOf(javaClass)?.let(inProject) == true }
        .filter(::isPublicKotlin)

    /**
     * Each public supertype and member signature of [classes] that names a type matching [type]. With [stableOnly], a
     * member marked `@ExperimentalCoSecApi` is left out too: it promises no more than the type it names.
     */
    fun violations(
        classes: List<JavaClass>,
        type: Regex,
        stableOnly: Boolean = false
    ): List<String> = classes.flatMap { javaClass ->
        val supertypes = (listOfNotNull(javaClass.rawSuperclass.orElse(null)) + javaClass.rawInterfaces)
            .filter { type.containsMatchIn(it.name) }
            .map { "${javaClass.name} extends ${it.name}" }
        val members = (javaClass.methods + javaClass.constructors + javaClass.fields)
            .filter(::isPublic)
            .filterNot { stableOnly && isMarked(it, EXPERIMENTAL_API) }
            .map(::signature)
            .filter { type.containsMatchIn(it) }
        supertypes + members
    }

    /**
     * A type a public signature names, and where it comes from: [library] is the `group:artifact` of the Gradle-cache jar
     * that holds it, or null when the type is neither the JDK's nor CoSec's and no library can be told for it. A type
     * the rule cannot place is one it cannot vouch for, so the caller fails on it instead of skipping it.
     */
    class LibraryType(val where: String, val type: String, val library: String?)

    /**
     * Every type outside the JDK and CoSec's own modules that a public signature of [classes] names: the supertypes, and
     * the parameter, return and field types of the public members, type arguments and bounds included.
     */
    fun libraryTypes(classes: List<JavaClass>): List<LibraryType> = classes.flatMap { javaClass ->
        val supertypes = listOfNotNull(javaClass.superclass.orElse(null)) + javaClass.interfaces
        val members = (javaClass.methods + javaClass.constructors + javaClass.fields).filter(
            ::isPublic
        ).flatMap { member ->
            val types: List<JavaType> = when (member) {
                is JavaCodeUnit -> member.parameterTypes + listOfNotNull(
                    (member as? com.tngtech.archunit.core.domain.JavaMethod)?.returnType,
                )
                is JavaField -> listOf(member.type)
                else -> emptyList()
            }
            types.map { member.fullName to it }
        }
        (supertypes.map { javaClass.name to it } + members).flatMap { (where, type) ->
            type.allInvolvedRawTypes.filterNot(
                ::isJdkOrCoSec
            ).map { raw -> LibraryType(where, raw.name, libraryOf(raw)) }
        }
    }.distinctBy { Triple(it.where, it.type, it.library) }

    private val PRIMITIVES = setOf("boolean", "byte", "char", "short", "int", "long", "float", "double", "void")

    private fun isJdkOrCoSec(type: JavaClass): Boolean {
        val name = type.name
        return name in PRIMITIVES || name.startsWith("me.ahoo.cosec.") ||
            listOf("java.", "javax.", "jdk.", "sun.").any(name::startsWith) ||
            type.source.orElse(null)?.uri?.scheme == "jrt"
    }

    /**
     * `group:artifact` of the Gradle-cache jar that holds [type], else null. ArchUnit knows the jar of a type it imported;
     * for one it only resolved (a class a signature names, outside the imported modules) the loaded class says where it
     * comes from.
     */
    private fun libraryOf(type: JavaClass): String? {
        val uri = type.source.orElse(null)?.uri?.toString()
            ?: runCatching { type.reflect().protectionDomain.codeSource?.location?.toString() }.getOrNull()
            ?: return null
        // Gradle's cache keeps `files-2.1/<group>/<artifact>/<version>/<hash>/<file>.jar`; a local Maven repository
        // (`mavenLocal()`, which the build lists first) `<group as a path>/<artifact>/<version>/<file>.jar`.
        Regex("""/files-2\.1/([^/]+)/([^/]+)/[^/]+/[^/]+/[^/!]+\.jar""").find(uri)?.let {
            return "${it.groupValues[1]}:${it.groupValues[2]}"
        }
        return Regex("""/repository/(.+)/([^/]+)/[^/]+/[^/!]+\.jar""").find(uri)
            ?.let { "${it.groupValues[1].replace('/', '.')}:${it.groupValues[2]}" }
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

    /** A member the JVM makes visible that Kotlin (or the marker) does not make public. */
    private fun isInternalMember(member: JavaMember): Boolean {
        val modifiers = member.modifiers
        if (JavaModifier.SYNTHETIC in modifiers || JavaModifier.BRIDGE in modifiers) return true
        if (JavaModifier.PUBLIC !in modifiers && JavaModifier.PROTECTED !in modifiers) return true
        // A protected member of a final class has no subclass to be called from.
        if (JavaModifier.PUBLIC !in modifiers && JavaModifier.FINAL in member.owner.modifiers) return true
        if (member.isAnnotatedWith(INTERNAL_API)) return true
        // Kotlin mangles the names of internal functions and accessors with the module name.
        return '$' in member.name
    }

    /** Whether [member], or the property it is an accessor of, carries [marker]. */
    private fun isMarked(member: JavaMember, marker: String): Boolean {
        if (member.isAnnotatedWith(marker)) return true
        // A property's annotations live on its synthetic `getX$annotations` method, not on the getter.
        val getter = member.name.removePrefix("set").let { if (it != member.name) "get$it" else member.name }
        return member.owner.methods.any { it.name == getter + "\$annotations" && it.isAnnotatedWith(marker) }
    }

    private fun isPublic(member: JavaMember): Boolean {
        if (isInternalMember(member)) return false
        if (isMarked(member, INTERNAL_API)) return false
        val reflected = (member as? JavaCodeUnit)?.let { runCatching { it.reflect() }.getOrNull() }
        val visibility = when (reflected) {
            is Method -> reflected.kotlinFunction?.visibility
            is java.lang.reflect.Constructor<*> -> runCatching { reflected.kotlinFunction?.visibility }.getOrNull()
            else -> null
        }
        return visibility == null || visibility == KVisibility.PUBLIC || visibility == KVisibility.PROTECTED
    }

    private fun signature(member: JavaMember): String = when (member) {
        is JavaCodeUnit -> runCatching { (member.reflect() as Executable).toGenericString() }.getOrNull()
            ?: (member.fullName + member.rawReturnType.name)
        else -> member.fullName + (member as? JavaField)?.rawType?.name
    }
}
