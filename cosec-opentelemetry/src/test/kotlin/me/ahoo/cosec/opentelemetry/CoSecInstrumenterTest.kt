package me.ahoo.cosec.opentelemetry

import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class CoSecInstrumenterTest {

    @Test
    fun getVersion() {
        assertThat(CoSecInstrumenter.INSTRUMENTATION_VERSION, notNullValue())
    }
}
