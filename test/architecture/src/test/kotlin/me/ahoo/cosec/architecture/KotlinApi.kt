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
import com.tngtech.archunit.core.domain.JavaModifier
import kotlin.enums.EnumEntries
import kotlin.reflect.KCallable
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty
import kotlin.reflect.KVisibility
import kotlin.reflect.jvm.javaConstructor
import kotlin.reflect.jvm.javaGetter
import kotlin.reflect.jvm.javaMethod

/**
 * The public API of CoSec's published modules as Kotlin sees it, for the rules that read it through reflection: what is
 * public to Kotlin (an `internal` class or constructor is public in the bytecode), and what `@InternalCoSecApi` keeps
 * from applications. What reflection cannot read is recorded in [unread], never skipped.
 */
internal class KotlinApi {
    /** Why a declaration could not be read. A rule fails on any reason but one it names. */
    val unread = mutableListOf<String>()

    /** The public classes of the published modules that `@InternalCoSecApi` does not keep from applications. */
    val classes: List<JavaClass> = SourceRulesTest.CLASSES
        .filter { javaClass -> SourceRulesTest.projectOf(javaClass)?.let(PublicApiStabilityTest::published) == true }
        .filter { it.modifiers.contains(JavaModifier.PUBLIC) && !internal(it) }

    // Kotlin reflection reports a class it cannot load as an Error (KotlinReflectionInternalError); `unread` keeps it.
    @Suppress("TooGenericExceptionCaught")
    inline fun <T> reading(default: T, read: () -> T): T = try {
        read()
    } catch (e: Throwable) {
        unread += e.message.orEmpty()
        default
    }

    /** What `@InternalCoSecApi` marks, on the class or a class it is nested in. */
    fun internal(javaClass: JavaClass): Boolean =
        javaClass.isAnnotatedWith(INTERNAL_API) || javaClass.enclosingClass.orElse(null)?.let(::internal) == true

    /** Public to Kotlin, with every class it is nested in. */
    fun visible(type: Class<*>): Boolean {
        val own = reading(false) { type.kotlin.visibility == KVisibility.PUBLIC }
        return own && (type.enclosingClass?.let(::visible) ?: true)
    }

    /**
     * The members of [javaClass] marked `@InternalCoSecApi`, by JVM name and parameter types. The marker's retention is
     * `BINARY`, so reflection does not see it: it is read from the bytecode, where a property's marker sits on its
     * synthetic `…$annotations` method.
     */
    fun optedOut(javaClass: JavaClass): Set<String> = javaClass.codeUnits
        .filter { it.isAnnotatedWith(INTERNAL_API) }
        .map { key(it.name.removeSuffix(ANNOTATIONS), it.rawParameterTypes.map { type -> type.name }) }
        .toSet()

    /** Public or protected to Kotlin, and not opted out with `@InternalCoSecApi` ([opted]). */
    fun exposed(callable: KCallable<*>, opted: Set<String>): Boolean = reading(false) {
        val visible = callable.visibility == KVisibility.PUBLIC || callable.visibility == KVisibility.PROTECTED
        visible && jvmKey(callable)?.let { it in opted } != true
    }

    private fun key(name: String, parameters: List<String>) = "$name(${parameters.joinToString()})"

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

    companion object {
        const val INTERNAL_API = "me.ahoo.cosec.annotations.InternalCoSecApi"

        /** A type naming an optional dependency this classpath lacks (springdoc's): the one reason a read may fail. */
        const val CLASS_NOT_FOUND = "Class not found: "
        private const val ENTRIES = "entries"
        private const val ANNOTATIONS = "\$annotations"
    }
}
