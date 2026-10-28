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

package me.ahoo.cosec.control

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaCall.Predicates.target
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.domain.properties.HasName.Predicates.name
import com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching
import com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * The control plane is a library that adapters wire in (RFC 0007 §5.1): its code never references Spring, Reactor
 * (nor the coroutine and Reactive Streams bridges to it) or a Redis client, and never blocks or leaves its scope. The
 * Redis store is `CoCacheControlStore` in `cosec-cocache`; the HTTP routes are wired by the starter. `cosec-core` still
 * brings Reactor onto the classpath, so this checks references, not dependencies (Q20).
 *
 * The classes are imported from where the module's own classes are compiled, not by package, so a class `control/`
 * puts in another package is checked too, and is itself a violation.
 */
class ControlBoundaryTest {
    private val classes: JavaClasses = ClassFileImporter()
        .importUrl(CONTROL_MODULE.protectionDomain.codeSource.location)

    @Test
    fun importsControlClasses() {
        // Without this an empty import would make the rules below pass on nothing.
        classes.contain(CONTROL_MODULE).assert().isTrue()
    }

    @Test
    fun everyClassIsInTheControlPackage() {
        classes()
            .should().resideInAPackage("$CONTROL..")
            .because("the boundary rules are written for me.ahoo.cosec.control (RFC 0007 §5.1)")
            .check(classes)
    }

    @Test
    fun noSpringReactorOrRedisClients() {
        noClasses()
            .should().dependOnClassesThat().resideInAnyPackage(*FORBIDDEN)
            .because("the control plane stays free of Spring, Reactor and Redis clients (RFC 0007 §5.1, Q20)")
            .check(classes)
    }

    /** As in `runtime/` (RFC 0006 §7.1): only the WebMvc adapter blocks, and no work runs outside a supervised scope. */
    @Test
    fun noBlockingBridgeOrUnscopedCoroutines() {
        noClasses()
            .should().callMethodWhere(
                target(owner(name("kotlinx.coroutines.BuildersKt")))
                    .and(target(DescribedPredicate.describe("runBlocking") { it.name.startsWith("runBlocking") })),
            )
            .orShould().callMethodWhere(
                target(owner(name("kotlinx.coroutines.Dispatchers"))).and(target(name("getIO")))
            )
            .orShould().dependOnClassesThat().haveFullyQualifiedName("kotlinx.coroutines.GlobalScope")
            .because("the control plane neither blocks nor escapes its scope (RFC 0006 §7.1)")
            .check(classes)
    }

    /**
     * AOT (RFC 0007 §10, PR-9): a native image needs a hint for each class read reflectively. The control plane needs
     * none: it reads and writes JSON as Jackson trees with hand-written codecs, and loads nothing by name. This keeps it
     * so: a class bound with `readValue`, `treeToValue` or `convertValue`, a lookup by name or a `ServiceLoader` would
     * need a hint the starter does not register, and would fail only in a native image.
     */
    @Test
    fun nothingThatANativeImageWouldNeedAHintFor() {
        noClasses()
            .should().callMethodWhere(
                target(
                    DescribedPredicate.describe("reflective Jackson binding") {
                        it.name in setOf("readValue", "treeToValue", "convertValue", "readerFor", "valueToTree")
                    }
                ).and(target(owner(nameMatching("tools\\.jackson\\.databind\\..*")))),
            )
            .orShould().callMethodWhere(target(owner(name("java.lang.Class"))).and(target(name("forName"))))
            .orShould().dependOnClassesThat().haveFullyQualifiedName("java.util.ServiceLoader")
            .orShould().dependOnClassesThat().resideInAPackage("kotlin.reflect.full..")
            .because("the control plane needs no AOT hints (RFC 0007 §10)")
            .check(classes)
    }

    companion object {
        private const val CONTROL = "me.ahoo.cosec.control"

        /** `internal` to `cosec-control`, so named rather than referenced from this module. */
        private val CONTROL_MODULE: Class<*> = Class.forName("$CONTROL.ControlModule")

        /**
         * CoCache (`me.ahoo.cache`) is not here: `ControlStore` adapters may use it (RFC 0007 PR-4). Its Redis backends
         * are in `cosec-cocache`, and the Redis clients themselves stay forbidden.
         */
        private val FORBIDDEN = arrayOf(
            "org.springframework..",
            "reactor..",
            "kotlinx.coroutines.reactor..",
            "kotlinx.coroutines.reactive..",
            "org.reactivestreams..",
            "io.lettuce..",
            "redis.clients..",
            "org.redisson..",
        )
    }
}
