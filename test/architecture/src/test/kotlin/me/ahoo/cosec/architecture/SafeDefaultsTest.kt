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

import me.ahoo.cosec.login.token.MembershipRefresh
import me.ahoo.cosec.runtime.authn.TokenAuthenticator
import me.ahoo.cosec.runtime.token.TokenRevocation
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.cosec.testkit.PermittedRequest
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.kotlinFunction

/**
 * UA-1, UA-2: what decides whether a revoked token, or a role removed from a user, still counts is never a default
 * argument. A reader of revocations ([RevocationReader], [TokenRevocation]) or a refresh's membership lookup
 * ([MembershipRefresh]) left out would mean "nothing is revoked" or "keep the old roles" without the caller saying so;
 * `RevocationReader.NONE` and `MembershipRefresh.CLAIMS` stay available, written where they are chosen.
 */
class SafeDefaultsTest {
    private val api = KotlinApi()

    /** What is `@InternalCoSecApi` is left out: an application never builds it (`ChangeAuthority`, the starter's). */
    @Test
    fun `no public parameter that reads revocations or memberships has a default`() {
        val found = api.classes.flatMap { javaClass ->
            api.reading(emptyList()) { defaulted(javaClass.name, api.optedOut(javaClass)) }
        }.distinct().sorted()
        found.assert()
            .describedAs("a revocation reader or a membership refresh with a default: make the caller name it")
            .isEmpty()
    }

    /** The scan reads the declarations it is about: a regression of any of them would be seen. */
    @Test
    fun `the scan reads the token API`() {
        TokenAuthenticator::class.primaryConstructor!!.parameters.single { it.name == "revocations" }
            .isOptional.assert().isFalse()
        PermittedRequest::withToken.parameters.single { it.name == "revocations" }.isOptional.assert().isFalse()
        val factory = Class.forName("me.ahoo.cosec.login.token.RefreshTokenAuthenticationKt").declaredMethods
            .single { it.name == "RefreshTokenAuthentication" && !it.isSynthetic }.kotlinFunction!!
        factory.parameters.single { it.name == "memberships" }.isOptional.assert().isFalse()
    }

    private fun defaulted(name: String, opted: Set<String>): List<String> {
        val type = Class.forName(name, false, Thread.currentThread().contextClassLoader)
        val metadata = type.getAnnotation(Metadata::class.java) ?: return emptyList()
        val callables: List<KCallable<*>> = when (metadata.kind) {
            CLASS -> if (api.visible(type)) type.kotlin.let { it.members + it.constructors } else emptyList()
            FILE, MULTIFILE_PART -> topLevel(type)
            else -> emptyList()
        }
        return callables.filter { api.exposed(it, opted) }.flatMap { callable ->
            callable.parameters.filter { it.isOptional && (it.type.classifier as? KClass<*>) in DECISIVE }
                .map { "$name.${callable.name}(${it.name})" }
        }
    }

    private fun topLevel(type: Class<*>): List<KCallable<*>> = type.declaredMethods
        .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.isBridge }
        .mapNotNull { api.reading(null) { it.kotlinFunction } }

    private companion object {
        val DECISIVE: Set<KClass<*>> = setOf(RevocationReader::class, TokenRevocation::class, MembershipRefresh::class)

        const val CLASS = 1
        const val FILE = 2
        const val MULTIFILE_PART = 5
    }
}
