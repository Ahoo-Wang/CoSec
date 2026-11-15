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
import java.lang.reflect.Modifier
import kotlin.enums.EnumEntries
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KProperty
import kotlin.reflect.KType
import kotlin.reflect.KVisibility
import kotlin.reflect.full.allSupertypes
import kotlin.reflect.jvm.javaConstructor
import kotlin.reflect.jvm.javaGetter
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.jvm.javaSetter
import kotlin.reflect.jvm.kotlinFunction
import kotlin.reflect.jvm.kotlinProperty

/**
 * RFC 0013 §7.3: no public or SPI signature of a published module names a `kotlin.time` type. Durations, instants and
 * clocks are `java.time`'s, the one kind of each an application has. Read from the Kotlin metadata: a value-class
 * parameter such as `kotlin.time.Duration` compiles to a `long`, so neither the descriptor nor an ABI dump line shows
 * it. What is `@InternalCoSecApi`, or internal, is left out.
 */
class KotlinTimeSignatureTest {
    @Test
    fun `no public signature names a kotlin time type`() {
        val classes = SourceRulesTest.CLASSES
            .filter { javaClass -> SourceRulesTest.projectOf(javaClass)?.let(PublicApiStabilityTest::published) == true }
            .filter { it.modifiers.contains(com.tngtech.archunit.core.domain.JavaModifier.PUBLIC) && !internal(it) }
        classes.assert().isNotEmpty()
        val offenders = classes.flatMap { offenders(it) }.distinct().sorted()
        offenders.assert()
            .describedAs("public signatures naming kotlin.time; use java.time (RFC 0013 §7.3)")
            .isEmpty()
        // What could not be read is reported, never skipped: only a type naming an optional dependency this classpath
        // lacks (springdoc's) may go unread.
        unread.filterNot { it.startsWith(CLASS_NOT_FOUND) }.assert()
            .describedAs("declarations reflection could not read; read them, or name why they may go unread")
            .isEmpty()
        read.assert().isGreaterThan(classes.size / 2)
    }

    /** Why a type could not be read: it names a class of an optional dependency that is not on this classpath. */
    private val unread = mutableListOf<String>()

    /** The classes and file facades whose declarations were read. */
    private var read = 0

    // Kotlin reflection reports a class it cannot load as an Error (KotlinReflectionInternalError); `unread` keeps it.
    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> reading(default: T, read: () -> T): T = try {
        read()
    } catch (e: Throwable) {
        unread += e.message.orEmpty()
        default
    }

    private fun internal(javaClass: JavaClass): Boolean =
        javaClass.isAnnotatedWith(INTERNAL_API) || javaClass.enclosingClass.orElse(null)?.let(::internal) == true

    private fun offenders(javaClass: JavaClass): List<String> {
        val opted = optedOut(javaClass)
        val type = Class.forName(javaClass.name, false, Thread.currentThread().contextClassLoader)
        val metadata = type.getAnnotation(Metadata::class.java) ?: return emptyList()
        val callables: List<KCallable<*>> = when (metadata.kind) {
            // A class: its own members and constructors, and what its supertypes say of it.
            CLASS -> {
                if (!visible(type)) {
                    return emptyList()
                }
                val kClass = reading(null) { type.kotlin.also { it.members } } ?: return emptyList()
                read += 1
                val supertypes = reading(emptyList()) {
                    kClass.allSupertypes.filter { names(it) }.map { "${kClass.qualifiedName} : $it" }
                }
                val members = reading(emptyList()) { kClass.members.toList() + kClass.constructors }
                return supertypes + members.filter { exposed(it, opted) }.flatMap { described(kClass.qualifiedName, it) }
            }
            // A file of top-level declarations.
            FILE, MULTIFILE_PART -> {
                read += 1
                topLevel(type)
            }
            // Lambdas and other synthetic classes declare nothing an application calls.
            else -> emptyList()
        }
        return callables.filter { exposed(it, opted) }.flatMap { described(type.name, it) }
    }

    /**
     * The public top-level functions and properties of a file facade. Synthetic members (default-argument bridges, the
     * `$$delegatedProperties` array) are left out by what they are; every other public method must map to a Kotlin
     * function or be an accessor of a property found through its field, and one that does neither is recorded.
     */
    private fun topLevel(type: Class<*>): List<KCallable<*>> {
        val properties = type.declaredFields.filterNot { it.isSynthetic }
            .mapNotNull { field -> reading(null) { field.kotlinProperty } }
        val accessors = properties.flatMap {
            listOfNotNull(
                it.javaGetter?.name,
                (it as? KMutableProperty<*>)?.javaSetter?.name
            )
        }
            .toSet()
        val functions = type.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.isBridge && it.name !in accessors }
            .mapNotNull { method ->
                reading(null) { method.kotlinFunction ?: error("$NO_DECLARATION${type.name}.${method.name}") }
            }
        return properties + functions
    }

    /** Public to Kotlin, with every class it is nested in: an `internal` class is public in the bytecode. */
    private fun visible(type: Class<*>): Boolean {
        val own = reading(false) { type.kotlin.visibility == KVisibility.PUBLIC }
        return own && (type.enclosingClass?.let(::visible) ?: true)
    }

    /**
     * The members of [javaClass] marked `@InternalCoSecApi`, by JVM name and parameter types. The marker's retention is
     * `BINARY`, so reflection does not see it: it is read from the bytecode, where a property's marker sits on its
     * synthetic `…$annotations` method.
     */
    private fun optedOut(javaClass: JavaClass): Set<String> = javaClass.codeUnits
        .filter { it.isAnnotatedWith(INTERNAL_API) }
        .map { key(it.name.removeSuffix(ANNOTATIONS), it.rawParameterTypes.map { type -> type.name }) }
        .toSet()

    private fun key(name: String, parameters: List<String>) = "$name(${parameters.joinToString()})"

    /** Public or protected, and not opted out with `@InternalCoSecApi` ([opted]). */
    private fun exposed(callable: KCallable<*>, opted: Set<String>): Boolean = reading(false) {
        val visible = callable.visibility == KVisibility.PUBLIC || callable.visibility == KVisibility.PROTECTED
        visible && jvmKey(callable)?.let { it in opted } != true
    }

    /** An enum's `entries` has a static accessor reflection does not map, and nothing can mark it: never opted out. */
    private fun jvmKey(callable: KCallable<*>): String? = when {
        callable is KProperty<*> && callable.name == ENTRIES && callable.returnType.classifier == EnumEntries::class -> null
        else -> jvmKeyOf(callable)
    }

    private fun jvmKeyOf(callable: KCallable<*>): String? = when (callable) {
        is KProperty<*> -> callable.javaGetter?.let { key(it.name, emptyList()) }
        is KFunction<*> -> callable.javaMethod?.let { method -> key(method.name, method.parameterTypes.map { it.name }) }
            ?: callable.javaConstructor?.let { constructor -> key("<init>", constructor.parameterTypes.map { it.name }) }
        else -> null
    }

    private fun described(owner: String?, callable: KCallable<*>): List<String> = reading(emptyList()) {
        val named = (callable.parameters.map { it.type } + callable.returnType).filter(::names)
        if (named.isEmpty()) emptyList() else listOf("$owner.${callable.name}: ${named.joinToString()}")
    }

    /** Whether [type], or one of its type arguments, is a `kotlin.time` type. */
    private fun names(type: KType): Boolean =
        (type.classifier as? KClass<*>)?.qualifiedName?.startsWith(KOTLIN_TIME) == true ||
            type.arguments.any { argument -> argument.type?.let(::names) == true }

    private companion object {
        const val INTERNAL_API = "me.ahoo.cosec.annotations.InternalCoSecApi"
        const val KOTLIN_TIME = "kotlin.time."
        const val CLASS_NOT_FOUND = "Class not found: "
        const val NO_DECLARATION = "No Kotlin declaration for "
        const val ENTRIES = "entries"
        const val ANNOTATIONS = "\$annotations"
        const val CLASS = 1
        const val FILE = 2
        const val MULTIFILE_PART = 5
    }
}
