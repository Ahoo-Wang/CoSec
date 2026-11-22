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

@file:OptIn(ExperimentalCoSecApi::class, InternalCoSecApi::class)

package me.ahoo.cosec.architecture

import com.tngtech.archunit.core.domain.JavaCall.Predicates.target
import com.tngtech.archunit.core.domain.properties.HasName.Predicates.name
import com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner
import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.http.Outcome
import me.ahoo.cosec.runtime.http.Problem
import me.ahoo.cosec.runtime.http.ProblemBodies
import me.ahoo.cosec.runtime.http.ProblemEncoder
import me.ahoo.cosec.runtime.http.ProblemRenderer

/**
 * One decision path (RFC 0006 §6.3; `PIP-1`): `StatusMapping` is the only constructor of [Outcome] and [Problem] in
 * any CoSec module on the classpath, and the web adapters never reference a decision or its cause: they write the
 * outcome they are given.
 */
@AnalyzeClasses(packages = ["me.ahoo.cosec"], importOptions = [DoNotIncludeTests::class])
class OneDecisionPathTest {
    @ArchTest
    val onlyStatusMappingConstructsOutcomes: ArchRule = noClasses()
        .that().doNotHaveFullyQualifiedName(STATUS_MAPPING.name)
        .should().callConstructorWhere(target(owner(name(Outcome::class.java.name))))
        .orShould().callConstructorWhere(target(owner(name(Problem::class.java.name))))
        .because("status codes and problem bodies have one definition (RFC 0006 §6.3)")

    @ArchTest
    val adaptersNeverSeeDecisions: ArchRule = noClasses()
        .that().resideInAnyPackage(*ADAPTERS)
        .should().dependOnClassesThat().resideInAnyPackage(*DECISIONS)
        .because("adapters translate an Outcome; they never decide (RFC 0006 §6.3)")

    /** §6.3: the bridges write the bytes `ProblemBodies` makes (`ProblemEncoder`'s, or the host's renderer's), never a body of their own. */
    @ArchTest
    val bridgesWriteOnlyEncodedProblems: ArchRule = noClasses()
        .that().resideInAnyPackage(*ADAPTERS)
        .and().haveSimpleNameStartingWith("Pipeline")
        .or().haveSimpleNameStartingWith("ReactivePipelineBridge")
        .should().dependOnClassesThat().resideInAnyPackage("tools.jackson..", "com.fasterxml.jackson..")
        .orShould().dependOnClassesThat().haveFullyQualifiedName("org.springframework.http.ProblemDetail")
        .orShould().dependOnClassesThat().haveFullyQualifiedName("me.ahoo.cosec.document.json.CoSecJson.mapper")
        .because("the problem body has one encoder, ProblemEncoder, or the host's renderer (RFC 0006 §6.2, §6.3; PIP-34)")

    /**
     * `PIP-34`: a refusal's body is rendered from its [Problem] alone. Neither the host's [ProblemRenderer] nor
     * [ProblemBodies], the one place it is called, can reach a decision, its cause or anything of the model.
     */
    @ArchTest
    val renderersNeverSeeDecisions: ArchRule = noClasses()
        .that().haveFullyQualifiedName(ProblemRenderer::class.java.name)
        .or().haveFullyQualifiedName(ProblemBodies::class.java.name)
        .should().dependOnClassesThat().resideInAnyPackage(*DECISIONS)
        .because("a renderer is given the problem a client may learn, never why it was refused (PIP-34, P16)")

    /**
     * `PIP-34`: the adapters and the login routes make a refusal's body only through [ProblemBodies], so one renderer
     * answers alike behind all of them, and a renderer that fails falls back to CoSec's body everywhere.
     */
    @ArchTest
    val refusalBodiesComeFromProblemBodies: ArchRule = noClasses()
        .that().resideInAnyPackage(*ADAPTERS, "me.ahoo.cosec.login..", "me.ahoo.cosec.spring.boot..")
        .should().callMethod(ProblemEncoder::class.java, "encode", Problem::class.java)
        .because("a refusal's body has one path, ProblemBodies (PIP-34)")

    private companion object {
        /** `internal` to `cosec-runtime`, so loaded by name: a rename fails here rather than passing vacuously. */
        val STATUS_MAPPING: Class<*> = Class.forName("me.ahoo.cosec.runtime.http.StatusMapping")

        val ADAPTERS = arrayOf("me.ahoo.cosec.webflux..", "me.ahoo.cosec.webmvc..", "me.ahoo.cosec.gateway..")

        /** The packages of the decision types. */
        val DECISIONS = arrayOf("me.ahoo.cosec.model.decision..", "me.ahoo.cosec.model")
    }
}
