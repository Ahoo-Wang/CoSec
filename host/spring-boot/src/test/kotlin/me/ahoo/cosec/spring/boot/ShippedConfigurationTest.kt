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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.spring.boot

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.FileSystemResource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readLines

/**
 * UB-22: what the repository ships as configuration, the gateway image's and the control server image's own `application.yaml`, the Kubernetes base's
 * and the control plane's, and every `COSEC_*` variable in `k8s/`, is held to the starter's startup checks: no key CoSec does not read
 * ([UnknownProperties]), no switch with a value it cannot read ([CoSecSwitches]). A property renamed without them
 * fails here, not as a crash loop of the image.
 */
class ShippedConfigurationTest {
    private val repository: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("settings.gradle.kts")) }

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoSecPropertyCheckAutoConfiguration::class.java))

    private fun yaml(file: Path): ApplicationContextRunner {
        Files.exists(file).assert().describedAs("$file exists").isTrue()
        val sources = YamlPropertySourceLoader().load(file.name, FileSystemResource(file))
        return runner.withInitializer { context -> sources.forEach { context.environment.propertySources.addLast(it) } }
    }

    @Test
    fun `the image's own application yaml is read by the starter`() {
        yaml(repository.resolve("platform/gateway-server/src/main/resources/application.yaml")).run { context ->
            context.startupFailure.assert().isNull()
        }
    }

    @Test
    fun `the control server image's own application yaml is read by the starter`() {
        yaml(repository.resolve("platform/control-server/src/main/resources/application.yaml")).run { context ->
            context.startupFailure.assert().isNull()
        }
    }

    @Test
    fun `the control plane's application yaml of the Kubernetes manifests is read by the starter`() {
        yaml(repository.resolve("k8s/control/application.yaml")).run { context ->
            context.startupFailure.assert().isNull()
        }
    }

    @Test
    fun `the Kubernetes base's application yaml is read by the starter`() {
        yaml(repository.resolve("k8s/base/application.yaml")).run { context ->
            context.startupFailure.assert().isNull()
        }
    }

    @Test
    fun `every COSEC variable in k8s names a property the starter reads`() {
        val variables = linkedMapOf<String, Any>()
        Files.walk(repository.resolve("k8s")).use { paths ->
            paths.filter { !it.isDirectory() && it.extension == "yaml" }.forEach { file ->
                val lines = file.readLines()
                lines.forEachIndexed { index, line ->
                    // `- name: COSEC_X` with its `value:` on the next line, and `- COSEC_X=value` literals.
                    NAME.find(line)?.let { match ->
                        val value = lines.getOrNull(index + 1)?.let { VALUE.find(it)?.groupValues?.get(1) } ?: "x"
                        variables[match.groupValues[1]] = value.trim('"', '\'')
                    }
                    LITERAL.find(line)?.let { variables[it.groupValues[1]] = it.groupValues[2].trim('"', '\'') }
                }
            }
        }
        variables.keys.assert().describedAs("the COSEC variables of k8s/").contains("COSEC_JWT_SECRET")
        runner.withInitializer { context ->
            context.environment.propertySources.addFirst(
                SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables)
            )
        }.run { context -> context.startupFailure.assert().isNull() }
    }

    @Test
    fun `a key the starter does not read in a shipped file would fail this test`() {
        val file = Files.createTempFile("shipped", ".yaml")
        try {
            Files.writeString(file, "cosec:\n  jwt:\n    secrt: x\n  audit:\n    enabled: on\n")
            yaml(file).run { context ->
                context.startupFailure.assert().isNotNull()
                generateSequence<Throwable>(context.startupFailure) { it.cause }.last().message.orEmpty()
                    .assert().contains("cosec.jwt.secrt")
            }
        } finally {
            Files.delete(file)
        }
    }

    private companion object {
        val NAME = Regex("^\\s*-\\s*name:\\s*(COSEC_[A-Z0-9_]+)\\s*$")
        val VALUE = Regex("^\\s*value:\\s*(.+?)\\s*$")
        val LITERAL = Regex("^\\s*-\\s*(COSEC_[A-Z0-9_]+)=(.*)$")
    }
}
