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

package me.ahoo.cosec.engine

import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

/**
 * The engine and its model are pure (RFC 0002 §2.3): no Reactor, Spring, logging, I/O, threads or clocks.
 *
 * Packages are exact unless they end in `..`, so `java.util` does not admit `java.util.concurrent`. Before adding a
 * package to [onlyStdlib], check that nothing in it does I/O, and say why in the pull request.
 */
@AnalyzeClasses(packages = ["me.ahoo.cosec.model", "me.ahoo.cosec.engine"], importOptions = [DoNotIncludeTests::class])
class EnginePurityTest {
    @ArchTest
    val onlyStdlib: ArchRule = classes().should().onlyDependOnClassesThat().resideInAnyPackage(
        "me.ahoo.cosec.model..",
        "me.ahoo.cosec.engine..",
        "kotlin",
        "kotlin.collections..",
        "kotlin.jvm..",
        "kotlin.ranges..",
        "kotlin.sequences..",
        "kotlin.text..",
        "kotlin.enums..",
        "kotlin.internal..",
        "java.lang",
        "java.util",
        "org.jetbrains.annotations..",
    )

    /** `java.lang` and `java.util` still hold impure classes: threads, clocks, processes, reflection, concurrency. */
    @ArchTest
    val noImpureJdk: ArchRule = noClasses().should().dependOnClassesThat().belongToAnyOf(
        Thread::class.java,
        System::class.java,
        Runtime::class.java,
        ProcessBuilder::class.java,
        ThreadLocal::class.java,
        java.util.Timer::class.java,
        java.util.Random::class.java,
    ).orShould().dependOnClassesThat().resideInAnyPackage("java.lang.reflect..", "java.util.concurrent..")
}
