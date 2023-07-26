package me.ahoo.cosec.generator

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.Paths
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
                .summary("test2")
                .get(Operation())
                .also {
                    paths.addPathItem("/test2", it)
                }
        }

        val policy = OpenAPIPolicyGenerator.generate(openAPI)
        assertThat(policy.id, equalTo(OpenAPIPolicyGenerator.POLICY_ID))
        assertThat(policy.category, equalTo(OpenAPIPolicyGenerator.POLICY_CATEGORY))
        assertThat(policy.name, equalTo(OpenAPIPolicyGenerator.POLICY_NAME))
        assertThat(policy.description, equalTo(OpenAPIPolicyGenerator.POLICY_DESCRIPTION))
        assertThat(policy.statements, hasSize(2))
        assertThat(policy.statements.first().name, equalTo("get"))
        assertThat(policy.statements.last().name, equalTo("test2"))
    }
}
