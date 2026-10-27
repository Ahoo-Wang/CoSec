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

package me.ahoo.cosec.runtime

import com.tngtech.archunit.core.domain.JavaCall.Predicates.target
import com.tngtech.archunit.core.domain.properties.HasName.Predicates.name
import com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner
import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

/**
 * One decision path (RFC 0006 §6.3; `PIP-1`): [StatusMapping] is the only constructor of [Outcome] and [Problem] in
 * any CoSec module on the classpath, and the web adapters never reference a decision or its cause: they write the
 * outcome they are given. The adapters are on this module's test classpath for this test alone.
 */
@AnalyzeClasses(packages = ["me.ahoo.cosec"], importOptions = [DoNotIncludeTests::class])
class OneDecisionPathTest {
    @ArchTest
    val onlyStatusMappingConstructsOutcomes: ArchRule = noClasses()
        .that().doNotHaveFullyQualifiedName(StatusMapping::class.java.name)
        .should().callConstructorWhere(target(owner(name(Outcome::class.java.name))))
        .orShould().callConstructorWhere(target(owner(name(Problem::class.java.name))))
        .because("status codes and problem bodies have one definition (RFC 0006 §6.3)")

    @ArchTest
    val adaptersNeverSeeDecisions: ArchRule = noClasses()
        .that().resideInAnyPackage(*ADAPTERS)
        .should().dependOnClassesThat().resideInAnyPackage(*DECISIONS)
        .because("adapters translate an Outcome; they never decide (RFC 0006 §6.3)")

    /** §6.3: the bridges write `ProblemEncoder`'s bytes, never a body of their own. */
    @ArchTest
    val bridgesWriteOnlyEncodedProblems: ArchRule = noClasses()
        .that().resideInAnyPackage(*ADAPTERS)
        .and().haveSimpleNameStartingWith("Pipeline")
        .or().haveSimpleNameStartingWith("ReactivePipelineBridge")
        .should().dependOnClassesThat().resideInAnyPackage("tools.jackson..", "com.fasterxml.jackson..")
        .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.http.ProblemDetail")
        .orShould().dependOnClassesThat().haveFullyQualifiedName("me.ahoo.cosec.serialization.CoSecJsonSerializer")
        .because("the problem body has one encoder, ProblemEncoder (RFC 0006 §6.2, §6.3)")

    private companion object {
        val ADAPTERS = arrayOf("me.ahoo.cosec.webflux..", "me.ahoo.cosec.servlet..", "me.ahoo.cosec.gateway..")

        /** Both decision models: the binding model's, and the layered one until the switch removes it. */
        val DECISIONS = arrayOf("me.ahoo.cosec.model.decision..", "me.ahoo.cosec.model")
    }
}
