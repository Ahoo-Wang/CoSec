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

package me.ahoo.cosec.spring.boot.starter.pipeline

import me.ahoo.cosec.gateway.IdentityHeadersFilter
import me.ahoo.cosec.gateway.IdentityStripFilter
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** Review of #1124: the mode is checked on every host, and a gateway strips identities whatever else is configured. */
class IdentityGuardAutoConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoSecIdentityGuardAutoConfiguration::class.java))

    private fun Throwable.messages() = generateSequence(this) { it.cause }.joinToString(" | ") { it.message.orEmpty() }

    @Test
    fun `an unknown mode fails startup instead of wiring nothing`() {
        runner.withPropertyValues("cosec.pipeline.mode=gatway").run { context ->
            context.startupFailure.assert().isNotNull()
            context.startupFailure!!.messages().assert().contains("gatway")
        }
    }

    @Test
    fun `gateway mode on a gateway host fails startup`() {
        runner.withPropertyValues("cosec.pipeline.mode=gateway").run { context ->
            context.startupFailure!!.messages().assert().contains("Spring Cloud Gateway host")
        }
        // Behind a gateway, the same mode is what it is for.
        runner.withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER))
            .withPropertyValues("cosec.pipeline.mode=gateway")
            .run { context -> context.startupFailure.assert().isNull() }
    }

    @Test
    fun `a gateway strips identities even with CoSec turned off`() {
        runner.withPropertyValues("cosec.enabled=false").run { context ->
            context.startupFailure.assert().isNull()
            context.getBean(IdentityStripFilter::class.java).assert().isNotNull()
            context.getBean(IdentityHeadersFilter::class.java).assert().isNotNull()
        }
        runner.withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER)).run { context ->
            context.getBeansOfType(IdentityStripFilter::class.java).assert().isEmpty()
        }
    }
}
