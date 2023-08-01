package me.ahoo.cosec.spring.boot.starter.authorization

import me.ahoo.cosec.spring.boot.starter.authorization.CoSecRequestParserAutoConfiguration.Companion.REACTIVE_REQUEST_PARSER_BEAN_NAME
import me.ahoo.cosec.spring.boot.starter.authorization.CoSecRequestParserAutoConfiguration.Companion.SERVLET_REQUEST_PARSER_BEAN_NAME
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
}
