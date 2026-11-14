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

package me.ahoo.cosec.spring.boot.starter.pipeline

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.ip2region.Ip2RegionAttributeProvider
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** Round 2 M5: the IPv6 database comes from cosec.ip2region.ipv6-location, beside the bundled IPv4 one. */
class Ip2RegionAutoConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoSecPipelineIntegrationAutoConfiguration::class.java))

    @Test
    fun `without a location only the bundled IPv4 database is loaded`() {
        runner.run { context ->
            context.getBean(Ip2RegionAttributeProvider::class.java).versions.assert().containsExactly("IPv4")
        }
    }

    @Test
    fun `a location that does not exist fails startup`() {
        runner.withPropertyValues("cosec.ip2region.ipv6-location=file:/no/such/ip2region_v6.xdb").run { context ->
            generateSequence(context.startupFailure) { it.cause }.any { it.message.orEmpty().contains("ipv6-location") }
                .assert().isTrue()
        }
    }
}
