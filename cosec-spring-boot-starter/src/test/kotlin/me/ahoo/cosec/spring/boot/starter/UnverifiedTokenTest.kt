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

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaCall.Predicates.target
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.domain.properties.HasName.Predicates.name
import com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching
import com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner
import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import me.ahoo.cosec.jwt.JwtTokenVerifier

/**
 * `PIP-11`: only the verifier turns a token into a principal. 7.x inject mode read a forwarded JWT without checking its
 * signature; nothing in CoSec may do that again. The classpath here holds every module the starter wires.
 */
@AnalyzeClasses(packages = ["me.ahoo.cosec"], importOptions = [DoNotIncludeTests::class])
class UnverifiedTokenTest {
    @ArchTest
    val onlyTheVerifierDecodesATokenIntoAPrincipal: ArchRule = noClasses()
        .that().doNotHaveFullyQualifiedName(VERIFIER)
        .and().doNotHaveFullyQualifiedName(JWTS)
        .should().callMethodWhere(decoding)
        .because("a token read without verifying its signature is whatever a client wrote (PIP-11)")

    /**
     * Within the verifier, only `verify` (which verifies the access token's signature first) and `refresh` (which
     * verifies the refresh token and the access token's signature first) may decode. Whether each verifies before it
     * decodes is in their bodies, which `JwtTokenVerifierTest` exercises with forged signatures.
     */
    @ArchTest
    val withinTheVerifierOnlyTheVerifyingMethodsDecode: ArchRule = methods()
        .that().areDeclaredIn(JwtTokenVerifier::class.java)
        .and().doNotHaveName("verify")
        .and().doNotHaveName("refresh")
        .should(notDecode())
        .because("only the methods that verify a signature may decode a token (PIP-11)")

    private fun notDecode() = object : ArchCondition<JavaMethod>("not decode a token") {
        override fun check(item: JavaMethod, events: ConditionEvents) {
            item.methodCallsFromSelf.filter { decoding.test(it) }.forEach {
                events.add(SimpleConditionEvent.violated(item, "${item.fullName} decodes: ${it.description}"))
            }
        }
    }

    private companion object {
        const val VERIFIER = "me.ahoo.cosec.jwt.JwtTokenVerifier"
        const val JWTS = "me.ahoo.cosec.jwt.Jwts"
        const val JWT = "com.auth0.jwt.JWT"
        const val PARSER = "com.auth0.jwt.impl.JWTParser"

        /** auth0's unverified decoders, its payload and header parser, and CoSec's own decoding. */
        val decoding: DescribedPredicate<JavaMethodCall> =
            target(owner(name(JWT))).and(target(nameMatching("decode.*")))
                .or(target(owner(name(PARSER))))
                .or(target(owner(name(JWTS))).and(target(nameMatching("(decode|toPrincipal).*"))))
                .forSubtype()
    }
}
