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

package me.ahoo.cosec.spring.boot.pipeline

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.support.SpringFactoriesLoader
import org.springframework.util.unit.DataSize
import java.util.Properties

/**
 * `IDN-43`: on a host that reads `CoSec-Identity`, the web server's request-header limit is derived from R when the
 * operator has not set it, and an operator's value too small for it is named at startup, and kept.
 */
class IdentityHeaderLimitTest {
    private fun environment(vararg properties: Pair<String, String>) = StandardEnvironment().apply {
        propertySources.addFirst(MapPropertySource("application", properties.toMap()))
        IdentityHeaderLimit().postProcessEnvironment(this, SpringApplication())
    }

    private fun StandardEnvironment.limit(): DataSize? =
        Binder.get(this).bind(IdentityHeaderLimit.SERVER_LIMIT_KEY, DataSize::class.java).orElse(null)

    @Test
    fun `a host that reads the identity gets R and the other headers' 8 KB, and says so - IDN-43`() {
        listOf(
            environment(PipelineProperties.MODE_KEY to "gateway"),
            environment(PipelineProperties.CALLER_KEY to "forwarded-identity"),
        ).forEach { environment ->
            environment.limit().assert().isEqualTo(DataSize.ofKilobytes(16))
            val (warn, line) = checkNotNull(IdentityHeaderLimit.check(environment))
            warn.assert().isFalse()
            line.assert().contains("16384 bytes, derived from cosec.pipeline.identity.max-size (8192 bytes)")
        }
        // A larger R takes the limit with it.
        environment(PipelineProperties.MODE_KEY to "gateway", "cosec.pipeline.identity.max-size" to "12KB")
            .limit().assert().isEqualTo(DataSize.ofKilobytes(20))
    }

    @Test
    fun `a host that does not read the identity keeps the server's own default - IDN-43`() {
        environment().limit().assert().isNull()
        environment(PipelineProperties.MODE_KEY to "enforce").limit().assert().isNull()
        environment(PipelineProperties.MODE_KEY to "gateway", "cosec.enabled" to "false").limit().assert().isNull()
    }

    @Test
    fun `an operator's limit is kept, and named at startup with both numbers when it is too small - IDN-43`() {
        val roomy = environment(GATEWAY, IdentityHeaderLimit.SERVER_LIMIT_KEY to "32KB")
        roomy.limit().assert().isEqualTo(DataSize.ofKilobytes(32))
        IdentityHeaderLimit.check(roomy).assert().isNull()

        val small = environment(GATEWAY, IdentityHeaderLimit.SERVER_LIMIT_KEY to "8KB")
        small.limit().assert().isEqualTo(DataSize.ofKilobytes(8))
        val (warn, line) = checkNotNull(IdentityHeaderLimit.check(small))
        warn.assert().isTrue()
        line.assert().contains("8192 bytes, below cosec.pipeline.identity.max-size (8192 bytes)")
        line.assert().contains("(16384 bytes)")
    }

    @Test
    fun `a host that reads the identity relays its callers within what it reads, or does not start - IDN-43`() {
        IdentityHeaderLimit.requireRelayWithinReads(environment(GATEWAY))
        val small = environment(GATEWAY, "cosec.pipeline.identity.max-size" to "4KB")
        assertThrownBy<IllegalStateException> { IdentityHeaderLimit.requireRelayWithinReads(small) }
            .hasMessageContaining("4096 bytes, below the 6144 bytes HttpControlService")
        // A host that does not read the identity has no R of its own to hold the relay to.
        IdentityHeaderLimit.requireRelayWithinReads(environment("cosec.pipeline.identity.max-size" to "4KB"))
    }

    @Test
    fun `a gateway writer limit above what every 8x service reads by default is named with both numbers - IDN-43`() {
        IdentityHeaderLimit.gatewayWriterLine(DataSize.ofKilobytes(6)).assert().isNull()
        IdentityHeaderLimit.gatewayWriterLine(DataSize.ofKilobytes(8)).assert().isNull()
        IdentityHeaderLimit.gatewayWriterLine(DataSize.ofKilobytes(12)).assert()
            .contains("12288 bytes, above the 8192 bytes an 8.x service reads by default")
    }

    @Test
    fun `the derivation is registered as Spring Boot's environment post-processor - IDN-43`() {
        val registered = javaClass.classLoader.getResources(SpringFactoriesLoader.FACTORIES_RESOURCE_LOCATION).toList()
            .flatMap { url ->
                val factories = Properties().apply { url.openStream().use(::load) }
                factories.getProperty(EnvironmentPostProcessor::class.java.name).orEmpty().split(',').map { it.trim() }
            }
        registered.assert().contains(IdentityHeaderLimit::class.java.name)
    }

    private companion object {
        val GATEWAY = PipelineProperties.MODE_KEY to "gateway"
    }
}
