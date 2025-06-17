package me.ahoo.cosec.generator

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.Paths
import me.ahoo.cosec.openapi.generator.OpenAPIPolicyGenerator
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class OpenAPIPolicyGeneratorTest {

    @Test
    fun generate() {
        val openAPI = OpenAPI().apply {
            paths = Paths()
            PathItem()
                .summary("test")
                .get(Operation().summary("get"))
                .also {
                    paths.addPathItem("/test", it)
                }
            PathItem()
                .get(Operation().summary("test2"))
                .also {
                    paths.addPathItem("/test2", it)
                }
            PathItem()
                .get(Operation().operationId("test3"))
                .also {
                    paths.addPathItem("/test3", it)
                }
        }

        val policy = OpenAPIPolicyGenerator.generate(openAPI)
        assertThat(policy.id, equalTo(OpenAPIPolicyGenerator.POLICY_ID))
        assertThat(policy.category, equalTo(OpenAPIPolicyGenerator.POLICY_CATEGORY))
        assertThat(policy.name, equalTo(OpenAPIPolicyGenerator.POLICY_NAME))
        assertThat(policy.description, equalTo(OpenAPIPolicyGenerator.POLICY_DESCRIPTION))
        assertThat(policy.statements, hasSize(3))
        assertThat(policy.statements[0].name, equalTo("get"))
        assertThat(policy.statements[1].name, equalTo("test2"))
        assertThat(policy.statements[2].name, equalTo("test3"))
    }
}
