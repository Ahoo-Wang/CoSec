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

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaCall.Predicates.target
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf
import com.tngtech.archunit.core.domain.JavaCodeUnit
import com.tngtech.archunit.core.domain.JavaModifier
import com.tngtech.archunit.core.domain.properties.HasName.Predicates.name
import com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner
import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.codeUnits
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import me.ahoo.cosec.engine.flat.FlatEngine
import me.ahoo.cosec.spi.snapshot.SnapshotChangeListener
import me.ahoo.cosec.spi.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.runtime.snapshot.SnapshotSource

/**
 * Reactor stays at the adapter bridges (RFC 0006 §5.1, Q6): the code of `runtime/`, `engine/` and `model/` never
 * references `reactor.*`, and no data-plane SPI signature carries a Reactor type. `runtime/` also stays out of its host
 * (Spring, the Servlet API, the coroutine bridges) and never uses `runCatching`. SPI implementations in other
 * modules may still await a Reactor-based client internally. A classpath check follows once cosec-core no
 * longer brings Reactor.
 */
@AnalyzeClasses(
    packages = ["me.ahoo.cosec.runtime", "me.ahoo.cosec.engine", "me.ahoo.cosec.model", "me.ahoo.cosec.spi"],
    importOptions = [DoNotIncludeTests::class],
)
class RuntimeBoundaryTest {
    @ArchTest
    val noReactorInPipelineModules: ArchRule = noClasses()
        .that().resideInAnyPackage(*PIPELINE_PACKAGES)
        .should().dependOnClassesThat().resideInAnyPackage(REACTOR)
        .because("only the WebFlux and Gateway adapters bridge to Reactor (RFC 0006 Q6)")

    /**
     * RFC 0006 §5.1, §7.2 T3 and §7.3: `runtime/` never knows its host. Spring and the Servlet API stay in the adapters
     * and the starter, and the Reactor bridge (`kotlinx.coroutines.reactor`) stays in the WebFlux and Gateway adapters.
     */
    @ArchTest
    val noHostTypesInRuntime: ArchRule = noClasses()
        .that().resideInAPackage("$RUNTIME..")
        .should().dependOnClassesThat().resideInAnyPackage(*HOST_PACKAGES)
        .because("adapters bridge to their host; runtime/ only sees HttpFacts and its own SPIs (RFC 0006 §5.1, §7.3)")

    /**
     * RFC 0006 §7.1 and §7.3: `runBlocking` is the WebMvc adapter's bridge, and CoSec uses neither `GlobalScope` nor
     * `Dispatchers.IO`; background work runs in the runtime's own supervised scope.
     */
    @ArchTest
    val noBridgeOrUnscopedCoroutinesInRuntime: ArchRule = noClasses()
        .that().resideInAPackage("$RUNTIME..")
        .should().callMethodWhere(
            target(owner(name("kotlinx.coroutines.BuildersKt")))
                .and(target(DescribedPredicate.describe("runBlocking") { it.name.startsWith("runBlocking") })),
        )
        .orShould().callMethodWhere(target(owner(name("kotlinx.coroutines.Dispatchers"))).and(target(name("getIO"))))
        .orShould().dependOnClassesThat().haveFullyQualifiedName("kotlinx.coroutines.GlobalScope")
        .because("only the WebMvc adapter blocks, and runtime/ owns the scope of its background work (RFC 0006 §7.1)")

    /**
     * Every public interface of `runtime/` is a data-plane SPI, so new ones are covered without editing this list.
     * The others live in modules that still hold Reactor; add each one here as it lands (RFC 0006 §5.1).
     */
    @ArchTest
    val noReactorInDataPlaneSignatures: ArchRule = codeUnits()
        .that().areDeclaredInClassesThat(dataPlaneSpi)
        .and().arePublic()
        .should(notMentionReactor())
        .because("data-plane SPIs are suspend functions or plain futures, never Reactor types (RFC 0006 Q6)")

    /**
     * RFC 0006 §7.4: `runCatching` catches `CancellationException`, so around a suspend call it turns an external
     * cancellation into a value and the pipeline would go on answering a request nobody waits for. Rethrow
     * `CancellationException`, then catch `Exception`. The rule reads bytecode: `runCatching` is inline and leaves a
     * call to `ResultKt.createFailure`, as do `Result.failure`, `mapCatching` and `recoverCatching`, so `runtime/`
     * expresses failures with its own sealed types instead. (A detekt `ForbiddenMethodCall` rule would need type
     * resolution, which detekt 1.23 does not manage against Kotlin 2.4.)
     */
    @ArchTest
    val noRunCatching: ArchRule = noClasses()
        .that().resideInAPackage("$RUNTIME..")
        .should().callMethodWhere(target(name("createFailure")).and(target(owner(name("kotlin.ResultKt")))))
        .because("runCatching swallows CancellationException (RFC 0006 §7.4)")

    private fun notMentionReactor() = object : ArchCondition<JavaCodeUnit>(
        "not mention a Reactor type in its signature"
    ) {
        override fun check(item: JavaCodeUnit, events: ConditionEvents) {
            val involved = item.returnType.allInvolvedRawTypes + item.parameterTypes.flatMap { it.allInvolvedRawTypes }
            involved.filter { it.isIn("reactor") }.forEach {
                events.add(SimpleConditionEvent.violated(item, "${item.fullName} mentions ${it.name}"))
            }
        }
    }

    private companion object {
        const val REACTOR = "reactor.."
        const val RUNTIME = "me.ahoo.cosec.runtime"

        fun JavaClass.isIn(root: String) = packageName == root || packageName.startsWith("$root.")
        val PIPELINE_PACKAGES = arrayOf("me.ahoo.cosec.runtime..", "me.ahoo.cosec.engine..", "me.ahoo.cosec.model..")
        val HOST_PACKAGES = arrayOf("org.springframework..", "jakarta.servlet..", "kotlinx.coroutines.reactor..")

        val dataPlaneSpi: DescribedPredicate<JavaClass> = belongToAnyOf(
            FlatEngine::class.java,
            SnapshotSource::class.java,
            SnapshotDocumentStore::class.java,
            SnapshotChangeListener::class.java,
        ).or(
            DescribedPredicate.describe("public interfaces of me.ahoo.cosec.runtime") {
                it.isInterface && it.modifiers.contains(JavaModifier.PUBLIC) && it.isIn(RUNTIME)
            },
        )
    }
}
