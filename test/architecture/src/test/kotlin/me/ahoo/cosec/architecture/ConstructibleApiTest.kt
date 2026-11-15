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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.full.companionObject

/**
 * What an application can call, it can call: every stable public constructor or companion factory of a published module
 * takes, for each parameter it requires, a CoSec type an application can obtain. That is an interface or an abstract
 * class it implements, an object or enum, a class with a constructor or factory of its own that `@InternalCoSecApi`
 * does not keep from it, or a type a stable public member answers (a `Pipeline` from its builder, a `PolicyDocument`
 * from its codec). A public class whose only constructor takes a type no application can build cannot be
 * assembled or tested without the starter (entry 12 of the 8.0 drill: `TokenCompositeAuthentication`).
 */
class ConstructibleApiTest {
    private val api = KotlinApi()

    /** The CoSec types a stable public function or property of a published class answers. */
    private val answered: Set<KClass<*>> by lazy {
        api.classes.flatMap { javaClass ->
            val type = Class.forName(javaClass.name, false, Thread.currentThread().contextClassLoader)
            if (type.getAnnotation(Metadata::class.java)?.kind != CLASS || !api.visible(type)) {
                return@flatMap emptyList()
            }
            val opted = api.optedOut(javaClass)
            api.reading(emptyList()) {
                type.kotlin.members.filter {
                    api.exposed(
                        it,
                        opted
                    )
                }.mapNotNull { it.returnType.classifier as? KClass<*> }
            }
        }.toSet()
    }

    @Test
    fun `every public constructor takes only what an application can build`() {
        val offenders = api.classes.flatMap(::offenders).distinct().sorted()
        offenders.assert()
            .describedAs("public constructors and factories that require a type no application can build")
            .isEmpty()
        api.unread.filterNot { it.startsWith(KotlinApi.CLASS_NOT_FOUND) }.assert()
            .describedAs("declarations reflection could not read; read them, or name why they may go unread")
            .isEmpty()
    }

    private fun offenders(javaClass: com.tngtech.archunit.core.domain.JavaClass): List<String> {
        val type = Class.forName(javaClass.name, false, Thread.currentThread().contextClassLoader)
        if (type.getAnnotation(Metadata::class.java)?.kind != CLASS || !api.visible(type)) {
            return emptyList()
        }
        val kClass = api.reading(null) { type.kotlin.also { it.constructors } } ?: return emptyList()
        val opted = api.optedOut(javaClass)
        val constructors = api.reading(emptyList()) { kClass.constructors.filter { api.exposed(it, opted) } }
        val factories = api.reading(emptyList()) { factoriesOf(kClass) }
        return (constructors + factories).flatMap { callable ->
            api.reading(emptyList()) {
                callable.parameters
                    .filter { it.kind == KParameter.Kind.VALUE && !it.isOptional }
                    .mapNotNull { parameter ->
                        val required = parameter.type.classifier as? KClass<*> ?: return@mapNotNull null
                        if (required.qualifiedName?.startsWith(COSEC) != true || obtainable(required)) {
                            null
                        } else {
                            "${kClass.qualifiedName}.${callable.name}(${parameter.name}: ${required.qualifiedName})"
                        }
                    }
            }
        }
    }

    /** The public functions of [kClass]'s companion that answer a [kClass]: its factories. */
    private fun factoriesOf(kClass: KClass<*>): List<KCallable<*>> {
        val companion = kClass.companionObject ?: return emptyList()
        val opted = SourceRulesTest.CLASSES.get(companion.java.name).let(api::optedOut)
        return companion.members.filter { it.returnType.classifier == kClass && api.exposed(it, opted) }
    }

    private fun obtainable(type: KClass<*>): Boolean {
        if (type.java.isInterface || type.java.isEnum || type.objectInstance != null) {
            return true
        }
        if (type.isSealed) {
            return type.sealedSubclasses.any(::obtainable)
        }
        val opted = SourceRulesTest.CLASSES.get(type.java.name).let(api::optedOut)
        return type.constructors.any { api.exposed(it, opted) } || factoriesOf(type).isNotEmpty() || type in answered
    }

    private companion object {
        const val COSEC = "me.ahoo.cosec."
        const val CLASS = 1
    }
}
