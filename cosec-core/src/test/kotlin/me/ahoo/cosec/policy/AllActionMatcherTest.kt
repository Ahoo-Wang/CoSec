package me.ahoo.cosec.policy

import io.mockk.mockk
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Test

internal class AllActionMatcherTest {
    @Test
    fun match() {
        assertThat(AllActionMatcher.type, `is`(AllActionMatcher.TYPE))
        assertThat(AllActionMatcher.pattern, `is`("*"))
        assertThat(AllActionMatcher.match(mockk(), mockk()), `is`(true))
    }
}
