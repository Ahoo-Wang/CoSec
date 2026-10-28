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

package me.ahoo.cosec.cache.snapshot

import me.ahoo.cosec.runtime.json.CoSecModule
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates
import org.springframework.core.io.support.SpringFactoriesLoader
import java.util.ServiceLoader

class SnapshotRuntimeHintsTest {
    private val hints = RuntimeHints().also { SnapshotRuntimeHints().registerHints(it, javaClass.classLoader) }

    @Test
    fun isRegisteredForTheAotProcessor() {
        SpringFactoriesLoader.forResourceLocation("META-INF/spring/aot.factories")
            .load(RuntimeHintsRegistrar::class.java)
            .map { it.javaClass.name }
            .assert().contains(SnapshotRuntimeHints::class.java.name)
    }

    @Test
    fun coversTheServiceFilesThatDecodingLoads() {
        SnapshotRuntimeHints.SERVICES.forEach { service ->
            RuntimeHintsPredicates.resource().forResource("META-INF/services/$service").test(hints)
                .assert().describedAs(service).isTrue()
        }
    }

    @Test
    fun coversEveryProviderOnTheClasspath() {
        val providers = listOf(CoSecModule::class.java) +
            ServiceLoader.load(tools.jackson.databind.JacksonModule::class.java).map { it.javaClass }
        providers.forEach { provider ->
            RuntimeHintsPredicates.reflection().onType(provider)
                .withMemberCategory(MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS)
                .test(hints)
                .assert().describedAs(provider.name).isTrue()
        }
    }

    @Test
    fun usesTheContextClassLoaderWhenNoneIsGiven() {
        val hints = RuntimeHints().also { SnapshotRuntimeHints().registerHints(it, null) }
        RuntimeHintsPredicates.reflection().onType(CoSecModule::class.java).test(hints).assert().isTrue()
    }
}
