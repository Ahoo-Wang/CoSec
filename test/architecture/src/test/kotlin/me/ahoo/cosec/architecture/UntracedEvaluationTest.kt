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

import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

/**
 * DR1-A-1: an evaluation with no trace (`TraceLevel.OFF`) builds no trace. That is how the untraced path allocates
 * nothing beyond what `Evaluator` documents, and only JMH would otherwise notice a break: the untraced evaluator and its
 * extension files (`Evaluator*`, their file classes and nested ones) reference no trace type and nothing of
 * `TracingEvaluator`, which overrides only the step per candidate.
 */
@AnalyzeClasses(packages = ["me.ahoo.cosec.engine"], importOptions = [DoNotIncludeTests::class])
class UntracedEvaluationTest {
    @ArchTest
    val theUntracedEvaluatorReadsNoTrace: ArchRule = noClasses()
        .that().resideInAPackage("me.ahoo.cosec.engine").and().haveSimpleNameStartingWith("Evaluator")
        .should().dependOnClassesThat().resideInAPackage("me.ahoo.cosec.engine.trace..")
        .orShould().dependOnClassesThat().haveSimpleNameStartingWith("TracingEvaluator")
}
