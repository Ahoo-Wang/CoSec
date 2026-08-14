package me.ahoo.cosec.spring.boot.starter.authorization

import me.ahoo.cosec.servlet.ServletXForwardedRemoteIpResolver
import me.ahoo.cosec.spring.boot.starter.authorization.CoSecRequestParserAutoConfiguration.Companion.REACTIVE_REQUEST_PARSER_BEAN_NAME
import me.ahoo.cosec.spring.boot.starter.authorization.CoSecRequestParserAutoConfiguration.Companion.SERVLET_REQUEST_PARSER_BEAN_NAME
import me.ahoo.cosec.webflux.ReactiveXForwardedRemoteIpResolver
import me.ahoo.test.asserts.assert
import org.assertj.core.api.AssertionsForInterfaceTypes
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class CoSecRequestParserAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .withUserConfiguration(
                CoSecRequestParserAutoConfiguration::class.java,
            )
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(CoSecRequestParserAutoConfiguration::class.java)
                    .hasBean(CoSecRequestParserAutoConfiguration.SERVLET_REMOTE_IP_RESOLVER_BEAN_NAME)
                    .hasBean(SERVLET_REQUEST_PARSER_BEAN_NAME)
                    .hasBean(CoSecRequestParserAutoConfiguration.REACTIVE_REMOTE_IP_RESOLVER_BEAN_NAME)
                    .hasBean(REACTIVE_REQUEST_PARSER_BEAN_NAME)
            }
    }

    @Test
    fun remoteIpResolverDefaultsToMaxTrustedIndexOne() {
        val autoConfiguration = CoSecRequestParserAutoConfiguration(AuthorizationProperties())
        val servletResolver =
            autoConfiguration.WebMvc().servletRemoteIpResolver() as ServletXForwardedRemoteIpResolver
        servletResolver.maxTrustedIndex.assert().isEqualTo(1)

        val reactiveResolver =
            autoConfiguration.WebFlux().reactiveRemoteIpResolver() as ReactiveXForwardedRemoteIpResolver
        reactiveResolver.maxTrustedIndex.assert().isEqualTo(1)
    }

    @Test
    fun remoteIpResolverRespectsMaxTrustedIndexProperty() {
        val properties = AuthorizationProperties().apply { remoteIp.maxTrustedIndex = 3 }
        val autoConfiguration = CoSecRequestParserAutoConfiguration(properties)
        val reactiveResolver =
            autoConfiguration.WebFlux().reactiveRemoteIpResolver() as ReactiveXForwardedRemoteIpResolver
        reactiveResolver.maxTrustedIndex.assert().isEqualTo(3)
    }
}
