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
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.jetty.servlet.JettyServletWebServerFactory
import org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.support.GenericApplicationContext
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.mock.env.MockEnvironment
import org.springframework.util.ReflectionUtils
import org.springframework.web.filter.ForwardedHeaderFilter
import org.springframework.web.server.adapter.ForwardedHeaderTransformer
import java.util.function.Supplier

/**
 * `PIP-16`: the client address is read once, by one party CoSec trusts, or startup fails. What each reading does on a
 * real server is in `RealServerClientIpTest`.
 */
class ClientIpReadingsTest {
    private fun reading(
        properties: Map<String, String> = emptyMap(),
        environment: Map<String, String> = emptyMap(),
        beans: GenericApplicationContext.() -> Unit = {},
    ): ClientIpReading {
        val env = MockEnvironment()
        properties.forEach { (key, value) -> env.setProperty(key, value) }
        env.propertySources.addFirst(
            SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environment)
        )
        val context = GenericApplicationContext().apply(beans)
        context.refresh()
        val runtime = RuntimeAuthorizationProperties()
        properties[ClientIpReadings.MAX_TRUSTED_INDEX]?.let { runtime.remoteIp.maxTrustedIndex = it.toInt() }
        return context.use { ClientIpReadings.of(env, it, runtime) }
    }

    private val tomcat: GenericApplicationContext.() -> Unit =
        { registerBean(TomcatServletWebServerFactory::class.java, Supplier { TomcatServletWebServerFactory(0) }) }
    private val jetty: GenericApplicationContext.() -> Unit =
        { registerBean(JettyServletWebServerFactory::class.java, Supplier { JettyServletWebServerFactory(0) }) }
    private val netty: GenericApplicationContext.() -> Unit =
        { registerBean(NettyReactiveWebServerFactory::class.java, Supplier { NettyReactiveWebServerFactory(0) }) }

    /** What Spring Boot's CloudPlatform reads to detect Kubernetes, where it turns on the native strategy itself. */
    private val kubernetes = mapOf("KUBERNETES_SERVICE_HOST" to "10.96.0.1", "KUBERNETES_SERVICE_PORT" to "443")

    @Test
    fun `without a strategy CoSec reads the peer and X-Forwarded-For`() {
        val reading = reading(beans = tomcat)
        reading.maxTrustedIndex.assert().isEqualTo(1)
        reading.peerIsConnection.assert().isTrue()
        reading(mapOf(ClientIpReadings.MAX_TRUSTED_INDEX to "0"), beans = netty).maxTrustedIndex.assert().isZero()
    }

    @Test
    fun `on Tomcat with the native strategy CoSec takes the valve's address`() {
        for (properties in listOf(
            mapOf("server.forward-headers-strategy" to "native"),
            mapOf("server.tomcat.remoteip.remote-ip-header" to "x-real-ip"),
        )) {
            val reading = reading(properties, beans = tomcat)
            reading.maxTrustedIndex.assert().isZero()
            reading.peerIsConnection.assert().isTrue()
        }
        // Inferred from the cloud platform, as Spring Boot infers it.
        reading(environment = kubernetes, beans = tomcat).maxTrustedIndex.assert().isZero()
    }

    @Test
    fun `on Tomcat with the native strategy an explicit max-trusted-index fails startup`() {
        assertThrownBy<ClientIpConfigurationException> {
            reading(mapOf(ClientIpReadings.MAX_TRUSTED_INDEX to "1"), kubernetes, tomcat)
        }.hasMessageContaining(
            "server.tomcat.remoteip.internal-proxies"
        ).hasMessageContaining(ClientIpReadings.MAX_TRUSTED_INDEX)
    }

    @Test
    fun `on Netty and Jetty with the native strategy CoSec reads the header only`() {
        for (server in listOf(netty, jetty)) {
            val reading = reading(mapOf("server.forward-headers-strategy" to "NATIVE"), beans = server)
            reading.maxTrustedIndex.assert().isEqualTo(1)
            reading.peerIsConnection.assert().isFalse()
            assertThrownBy<ClientIpConfigurationException> {
                reading(mapOf(ClientIpReadings.MAX_TRUSTED_INDEX to "0"), kubernetes, server)
            }.hasMessageContaining("cannot be read")
        }
    }

    @Test
    fun `the framework strategy fails startup`() {
        assertThrownBy<ClientIpConfigurationException> {
            reading(mapOf("server.forward-headers-strategy" to "framework"), beans = tomcat)
        }.hasMessageContaining("leftmost")
    }

    @Test
    fun `a forwarded-header filter or transformer of the application fails startup unless it only removes`() {
        assertThrownBy<ClientIpConfigurationException> {
            reading {
                registerBean(
                    "forwarded",
                    ForwardedHeaderTransformer::class.java,
                    Supplier { ForwardedHeaderTransformer() }
                )
            }
        }.hasMessageContaining("[forwarded]")
        assertThrownBy<ClientIpConfigurationException> {
            reading {
                registerBean(
                    "registration",
                    FilterRegistrationBean::class.java,
                    Supplier {
                        FilterRegistrationBean(ForwardedHeaderFilter())
                    }
                )
            }
        }
        assertThrownBy<ClientIpConfigurationException> {
            reading { registerBean("filter", ForwardedHeaderFilter::class.java, Supplier { ForwardedHeaderFilter() }) }
        }
        reading {
            registerBean(
                ForwardedHeaderTransformer::class.java,
                Supplier { ForwardedHeaderTransformer().apply { isRemoveOnly = true } }
            )
            registerBean(
                ForwardedHeaderFilter::class.java,
                Supplier { ForwardedHeaderFilter().apply { setRemoveOnly(true) } }
            )
        }.peerIsConnection.assert().isTrue()
    }

    /** What the detection reads by reflection exists on this Spring: a rename fails here, not with a bare NPE at startup. */
    @Test
    fun `the Spring members the detection reads exist`() {
        ReflectionUtils.findMethod(ForwardedHeaderTransformer::class.java, ClientIpReadings.TRANSFORMER_REMOVE_ONLY)
            .assert().isNotNull()
        ReflectionUtils.findField(
            ForwardedHeaderFilter::class.java,
            ClientIpReadings.FILTER_REMOVE_ONLY
        ).assert().isNotNull()
        ReflectionUtils.findMethod(
            FilterRegistrationBean::class.java,
            ClientIpReadings.REGISTRATION_FILTER
        ).assert().isNotNull()
    }

    @Test
    fun `the native strategy on a server CoSec cannot tell fails startup`() {
        assertThrownBy<ClientIpConfigurationException> {
            reading(environment = kubernetes)
        }.hasMessageContaining("server.forward-headers-strategy=none")
        reading(mapOf("server.forward-headers-strategy" to "none"), kubernetes).peerIsConnection.assert().isTrue()
    }

    @Test
    fun `the starter fails startup with the reason`() {
        WebApplicationContextRunner()
            .withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER))
            .withConfiguration(
                AutoConfigurations.of(
                    CoSecSnapshotAutoConfiguration::class.java,
                    CoSecPipelineAutoConfiguration::class.java,
                    CoSecPipelineWebAutoConfiguration::class.java,
                ),
            )
            .withPropertyValues("cosec.authorization.routes.location=", "server.forward-headers-strategy=framework")
            .run { context ->
                generateSequence(context.startupFailure) { it.cause }.any { it is ClientIpConfigurationException }
                    .assert().isTrue()
            }
    }
}
