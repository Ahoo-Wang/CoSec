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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.ip2region.Ip2RegionAttributeProvider
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** Round 2 M5: the IPv6 database comes from cosec.ip2region.ipv6-location, beside the bundled IPv4 one. */
class Ip2RegionAutoConfigurationTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoSecPipelineIntegrationAutoConfiguration::class.java))

    @Test
    fun `without a location only the bundled IPv4 database is loaded, and startup says so`() {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger(CoSecPipelineIntegrationAutoConfiguration::class.java) as Logger
        logger.addAppender(appender)
        try {
            runner.run { context ->
                context.getBean(Ip2RegionAttributeProvider::class.java).versions.assert().containsExactly("IPv4")
            }
        } finally {
            logger.detachAppender(appender)
        }
        val line = appender.list.single { it.formattedMessage.startsWith("ip2region databases") }
        line.level.assert().isEqualTo(Level.INFO)
        line.formattedMessage.assert().contains("[IPv4]", "The IPv6 addresses are unavailable", "ipv6-location")
    }

    /** The location is read; a second database of a version already loaded fails startup, one per version. */
    @Test
    fun `a location holding a database of the bundled version fails startup`() {
        runner.withPropertyValues("cosec.ip2region.ipv6-location=classpath:ip2region.xdb").run { context ->
            generateSequence(context.startupFailure) { it.cause }
                .any { it.message.orEmpty().contains("One ip2region database per IP version") }
                .assert().isTrue()
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
