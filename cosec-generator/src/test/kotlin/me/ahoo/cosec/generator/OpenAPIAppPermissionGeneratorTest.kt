package me.ahoo.cosec.generator

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.info.Info
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class OpenAPIAppPermissionGeneratorTest {

    @Test
    fun generate() {
        val openAPI = OpenAPI().apply {
            info = Info().title("test")
            paths = Paths()
            PathItem()
                .summary("test")
                .get(Operation().tags(listOf("test")).summary("get"))
                .post(Operation().tags(listOf("test")).summary("set"))
                .also {
                    paths.addPathItem("/test", it)
                }
            PathItem()
                .summary("test2")
                .get(Operation().tags(listOf("test2")))
                .also {
                    paths.addPathItem("/test2", it)
                }
        }

        val appPermission = OpenAPIAppPermissionGenerator.generate(openAPI)
        assertThat(appPermission.id, equalTo("test"))
        assertThat(appPermission.groups, hasSize(2))
    }
}
