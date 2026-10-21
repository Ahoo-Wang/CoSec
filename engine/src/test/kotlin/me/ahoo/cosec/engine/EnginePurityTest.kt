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

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaCall
import com.tngtech.archunit.core.domain.JavaCall.Predicates.target
import com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo
import com.tngtech.archunit.core.domain.JavaClass.Predicates.type
import com.tngtech.archunit.core.domain.properties.HasName.Predicates.name
import com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner
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

    /**
     * `java.lang` and `java.util` still hold impure classes: threads, clocks, processes, randomness, class loading,
     * service lookup, resource lookup, stack inspection, reflection, concurrency.
     */
    @ArchTest
    val noImpureJdk: ArchRule = noClasses().should().dependOnClassesThat().belongToAnyOf(
        Thread::class.java,
        System::class.java,
        Runtime::class.java,
        ProcessBuilder::class.java,
        ProcessHandle::class.java,
        ThreadLocal::class.java,
        InheritableThreadLocal::class.java,
        StackWalker::class.java,
        ClassLoader::class.java,
        java.util.Timer::class.java,
        java.util.Random::class.java,
        java.util.SplittableRandom::class.java,
        java.util.UUID::class.java,
        java.util.Date::class.java,
        java.util.Calendar::class.java,
        java.util.ServiceLoader::class.java,
        java.util.ResourceBundle::class.java,
    ).orShould().dependOnClassesThat().resideInAnyPackage("java.lang.reflect..", "java.util.concurrent..")

    /** Loading a class by name is reflection without `java.lang.reflect`. Plain class literals stay allowed. */
    @ArchTest
    val noClassForName: ArchRule = noClasses().should().callMethodWhere(call(Class::class.java, "forName"))

    /**
     * Impure methods of classes the engine may otherwise use: randomness, system properties, reflective construction,
     * monitors, the JVM's default locale and time zone, stack inspection and modules.
     */
    @ArchTest
    val noImpureCalls: ArchRule = noClasses().should().callMethodWhere(
        call(Math::class.java, "random")
            .or(call(StrictMath::class.java, "random"))
            .or(call(Integer::class.java, "getInteger"))
            .or(call(java.lang.Long::class.java, "getLong"))
            .or(call(java.lang.Boolean::class.java, "getBoolean"))
            .or(call(Class::class.java, "newInstance"))
            .or(call(Class::class.java, "getModule"))
            .or(call(Any::class.java, "wait", subclasses = true))
            .or(call(Throwable::class.java, "getStackTrace", subclasses = true))
            .or(call(java.util.Locale::class.java, "getDefault"))
            .or(call(java.util.TimeZone::class.java, "getDefault", subclasses = true)),
    )

    private fun call(owner: Class<*>, method: String, subclasses: Boolean = false): DescribedPredicate<JavaCall<*>> =
        target(name(method)).and(target(owner(if (subclasses) assignableTo(owner) else type(owner))))
}
