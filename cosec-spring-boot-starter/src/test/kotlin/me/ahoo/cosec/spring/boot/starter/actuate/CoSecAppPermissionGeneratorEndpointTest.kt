package me.ahoo.cosec.spring.boot.starter.actuate

import io.mockk.every
import io.mockk.mockk
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Paths
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class CoSecAppPermissionGeneratorEndpointTest {

    @Test
    fun generate() {
        val appPermission = CoSecAppPermissionGeneratorEndpoint(
            mockk {
                every { getIfAvailable() } returns OpenAPI().paths(Paths())
            }
        ).generate()

        assertThat(appPermission, notNullValue())
    }

    @Test
    fun generateIfNull() {
        val appPermission = CoSecAppPermissionGeneratorEndpoint(
            mockk {
                every { getIfAvailable() } returns null
            }
        ).generate()

        assertThat(appPermission, nullValue())
    }
}
