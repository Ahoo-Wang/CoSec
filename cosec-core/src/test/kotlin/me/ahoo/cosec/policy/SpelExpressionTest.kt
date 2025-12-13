package me.ahoo.cosec.policy

import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.policy.SpelExpression.Companion.isSpelTemplate
import me.ahoo.test.asserts.assert
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
        val securityContext = SimpleSecurityContext.anonymous()
        val templateExpression = "order/#{principal.id}/1".asTemplateExpression()
        templateExpression.getValue(securityContext).assert().isEqualTo("order/(0)/1")
        val constExpression = "order/1/1".asTemplateExpression()
        constExpression.getValue(securityContext).assert().isEqualTo("order/1/1")
    }

    data class Order(val id: Int)
}
