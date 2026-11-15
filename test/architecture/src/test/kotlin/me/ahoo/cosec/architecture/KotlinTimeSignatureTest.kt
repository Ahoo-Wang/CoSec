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
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KType
import kotlin.reflect.full.allSupertypes
import kotlin.reflect.jvm.javaGetter
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
        val classes = api.classes
        classes.assert().isNotEmpty()
        val offenders = classes.flatMap { offenders(it) }.distinct().sorted()
        offenders.assert()
            .describedAs("public signatures naming kotlin.time; use java.time (RFC 0013 §7.3)")
            .isEmpty()
        // What could not be read is reported, never skipped: only a type naming an optional dependency this classpath
        // lacks (springdoc's) may go unread.
        api.unread.filterNot { it.startsWith(KotlinApi.CLASS_NOT_FOUND) }.assert()
            .describedAs("declarations reflection could not read; read them, or name why they may go unread")
            .isEmpty()
        read.assert().isGreaterThan(classes.size / 2)
    }

    private val api = KotlinApi()

    /** The classes and file facades whose declarations were read. */
    private var read = 0

    private inline fun <T> reading(default: T, read: () -> T): T = api.reading(default, read)

    private fun offenders(javaClass: JavaClass): List<String> {
        val opted = api.optedOut(javaClass)
        val type = Class.forName(javaClass.name, false, Thread.currentThread().contextClassLoader)
        val metadata = type.getAnnotation(Metadata::class.java) ?: return emptyList()
        val callables: List<KCallable<*>> = when (metadata.kind) {
            // A class: its own members and constructors, and what its supertypes say of it.
            CLASS -> {
                if (!api.visible(type)) {
                    return emptyList()
                }
                val kClass = reading(null) { type.kotlin.also { it.members } } ?: return emptyList()
                read += 1
                val supertypes = reading(emptyList()) {
                    kClass.allSupertypes.filter { names(it) }.map { "${kClass.qualifiedName} : $it" }
                }
                val members = reading(emptyList()) { kClass.members.toList() + kClass.constructors }
                return supertypes + members.filter { api.exposed(it, opted) }.flatMap { described(kClass.qualifiedName, it) }
            }
            // A file of top-level declarations.
            FILE, MULTIFILE_PART -> {
                read += 1
                topLevel(type)
            }
            // Lambdas and other synthetic classes declare nothing an application calls.
            else -> emptyList()
        }
        return callables.filter { api.exposed(it, opted) }.flatMap { described(type.name, it) }
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

    private fun described(owner: String?, callable: KCallable<*>): List<String> = reading(emptyList()) {
        val named = (callable.parameters.map { it.type } + callable.returnType).filter(::names)
        if (named.isEmpty()) emptyList() else listOf("$owner.${callable.name}: ${named.joinToString()}")
    }

    /** Whether [type], or one of its type arguments, is a `kotlin.time` type. */
    private fun names(type: KType): Boolean =
        (type.classifier as? KClass<*>)?.qualifiedName?.startsWith(KOTLIN_TIME) == true ||
            type.arguments.any { argument -> argument.type?.let(::names) == true }

    private companion object {
        const val KOTLIN_TIME = "kotlin.time."
        const val NO_DECLARATION = "No Kotlin declaration for "
        const val CLASS = 1
        const val FILE = 2
        const val MULTIFILE_PART = 5
    }
}
