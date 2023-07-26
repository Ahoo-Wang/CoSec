package me.ahoo.cosec.spring.boot.starter.generator

import io.mockk.every
import io.mockk.mockk
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Paths
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class CoSecGeneratorEndpointTest {

    @Test
    fun generate() {
        val policy = CoSecGeneratorEndpoint(
            mockk {
                every { getIfAvailable() } returns OpenAPI().paths(Paths())
            }
        ).generate()

        assertThat(policy, notNullValue())
    }

    @Test
    fun generateIfNull() {
        val policy = CoSecGeneratorEndpoint(
            mockk {
                every { getIfAvailable() } returns null
            }
        ).generate()

        assertThat(policy, nullValue())
    }
}
