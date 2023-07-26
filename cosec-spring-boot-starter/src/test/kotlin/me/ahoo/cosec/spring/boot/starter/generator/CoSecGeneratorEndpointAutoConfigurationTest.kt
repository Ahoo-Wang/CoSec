package me.ahoo.cosec.spring.boot.starter.generator

import io.mockk.mockk
import io.swagger.v3.oas.models.OpenAPI
import org.assertj.core.api.AssertionsForInterfaceTypes
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class CoSecGeneratorEndpointAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .withBean(OpenAPI::class.java, { mockk<OpenAPI>() })
            .withUserConfiguration(CoSecGeneratorEndpointAutoConfiguration::class.java)
            .run { context: AssertableApplicationContext ->
                AssertionsForInterfaceTypes.assertThat(context)
                    .hasSingleBean(CoSecPolicyGeneratorEndpoint::class.java)
            }
    }
}
