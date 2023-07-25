package me.ahoo.cosec.policy

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.policy.SpelExpression.Companion.isSpelTemplate
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class SpelExpressionTest {
    @Test
    fun isTemplate() {
        assertThat("order:create".isSpelTemplate(), equalTo(false))
        assertThat(
            "order/#{context.principal.id}/1".isSpelTemplate(),
            equalTo(true)
        )
    }

    @Test
    fun getValue() {
        val securityContext = mockk<SecurityContext> {
            every { principal } returns mockk {
                every { id } returns "1"
            }
        }
        "order/#{principal.id}/1".asTemplateExpression()
            .let {
                assertThat(it.getValue(securityContext), equalTo("order/1/1"))
            }

        "order/1/1".asTemplateExpression()
            .let {
                assertThat(it.getValue(securityContext), equalTo("order/1/1"))
            }
    }
}
