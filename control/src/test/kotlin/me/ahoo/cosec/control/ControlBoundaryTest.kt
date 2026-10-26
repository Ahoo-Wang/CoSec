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

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * The control plane is a library that adapters wire in (RFC 0007 §5.1): its code never references Spring, Reactor
 * or a Redis client. The Redis store is `CoCacheControlStore` in `cosec-cocache`; the HTTP routes are wired by the
 * starter. `cosec-core` still brings Reactor onto the classpath, so this checks references, not dependencies (Q20).
 */
class ControlBoundaryTest {
    private val classes = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages(CONTROL)

    @Test
    fun importsControlClasses() {
        // Without this an empty import would make the rule below pass on nothing.
        classes.contain(ControlModule.javaClass).assert().isTrue()
    }

    @Test
    fun noSpringReactorOrRedisClients() {
        noClasses()
            .that().resideInAPackage("$CONTROL..")
            .should().dependOnClassesThat().resideInAnyPackage(*FORBIDDEN)
            .because("the control plane stays free of Spring, Reactor and Redis clients (RFC 0007 §5.1, Q20)")
            .check(classes)
    }

    companion object {
        private const val CONTROL = "me.ahoo.cosec.control"
        private val FORBIDDEN = arrayOf(
            "org.springframework..",
            "reactor..",
            "io.lettuce..",
            "redis.clients..",
            "org.redisson..",
        )
    }
}
