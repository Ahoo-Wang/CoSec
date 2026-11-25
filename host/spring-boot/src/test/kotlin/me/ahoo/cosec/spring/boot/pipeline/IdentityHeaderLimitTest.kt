/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.spring.boot.pipeline

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.web.server.autoconfigure.ServerProperties
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.util.unit.DataSize

/**
 * `IDN-43`: on a host that reads `CoSec-Identity`, the web server's request-header limit is derived from R on the bound
 * server properties when the operator has not set it, and the startup line states the limit in effect and where it came
 * from: an operator's value is kept and named as theirs, at WARN when too small for it; a value CoSec did not apply is
 * never called derived.
 */
class IdentityHeaderLimitTest {
    private fun environment(vararg properties: Pair<String, String>) = StandardEnvironment().apply {
        propertySources.addFirst(MapPropertySource("application", properties.toMap()))
    }

    /** The server's properties as Spring binds them from [environment], then as CoSec's post-processor leaves them. */
    private class Applied(environment: StandardEnvironment) {
        val limit = IdentityHeaderLimit(environment)
        val server = ServerProperties().apply {
            IdentityHeaderLimit.operatorLimit(environment)?.let { maxHttpRequestHeaderSize = it }
            limit.postProcessAfterInitialization(this, "serverProperties")
        }
        val line = IdentityHeaderLimit.line(environment, server.maxHttpRequestHeaderSize, limit.applied)
    }

    @Test
    fun `a host that reads the identity gets R and the other headers' 8 KB, and says so - IDN-43`() {
        listOf(
            environment(PipelineProperties.MODE_KEY to "gateway"),
            environment(PipelineProperties.CALLER_KEY to "forwarded-identity"),
        ).forEach { environment ->
            val applied = Applied(environment)
            applied.server.maxHttpRequestHeaderSize.assert().isEqualTo(DataSize.ofKilobytes(16))
            applied.limit.applied.assert().isEqualTo(DataSize.ofKilobytes(16))
            val (warn, line) = checkNotNull(applied.line)
            warn.assert().isFalse()
            line.assert().contains("16384 bytes, derived from cosec.pipeline.identity.max-size (8192 bytes)")
        }
        // A larger R takes the limit with it.
        Applied(environment(PipelineProperties.MODE_KEY to "gateway", "cosec.pipeline.identity.max-size" to "12KB"))
            .server.maxHttpRequestHeaderSize.assert().isEqualTo(DataSize.ofKilobytes(20))
    }

    @Test
    fun `a host that does not read the identity keeps the server's own default - IDN-43`() {
        listOf(
            environment(),
            environment(PipelineProperties.MODE_KEY to "enforce"),
            environment(PipelineProperties.MODE_KEY to "gateway", "cosec.enabled" to "false"),
        ).forEach { environment ->
            val applied = Applied(environment)
            applied.server.maxHttpRequestHeaderSize.assert().isEqualTo(ServerProperties().maxHttpRequestHeaderSize)
            applied.limit.applied.assert().isNull()
            applied.line.assert().isNull()
        }
        // Nor does it touch any other bean.
        val other = Any()
        val limit = IdentityHeaderLimit(environment(GATEWAY))
        limit.postProcessAfterInitialization(other, "other").assert().isSameAs(other)
    }

    @Test
    fun `an operator's limit is kept, and named at startup with both numbers when it is too small - IDN-43`() {
        val roomy = Applied(environment(GATEWAY, IdentityHeaderLimit.SERVER_LIMIT_KEY to "32KB"))
        roomy.server.maxHttpRequestHeaderSize.assert().isEqualTo(DataSize.ofKilobytes(32))
        roomy.limit.applied.assert().isNull()
        val (roomyWarn, roomyLine) = checkNotNull(roomy.line)
        roomyWarn.assert().isFalse()
        roomyLine.assert().contains("32768 bytes, as the operator set it").doesNotContain("derived from")

        val small = Applied(environment(GATEWAY, IdentityHeaderLimit.SERVER_LIMIT_KEY to "8KB"))
        small.server.maxHttpRequestHeaderSize.assert().isEqualTo(DataSize.ofKilobytes(8))
        val (warn, line) = checkNotNull(small.line)
        warn.assert().isTrue()
        line.assert().contains("8192 bytes, below cosec.pipeline.identity.max-size (8192 bytes)")
        line.assert().contains("(16384 bytes), as the operator set it").doesNotContain("derived from")
    }

    @Test
    fun `the startup line never says derived for a limit CoSec did not apply - IDN-43`() {
        val environment = environment(GATEWAY)
        // The server's properties were created before CoSec's post-processor could change them: Spring Boot's 8 KB.
        val (warn, line) = checkNotNull(IdentityHeaderLimit.line(environment, DataSize.ofKilobytes(8), null))
        warn.assert().isTrue()
        line.assert().contains("8192 bytes, not derived").doesNotContain("derived from")
        line.assert().contains("set server.max-http-request-header-size to at least 16384 bytes")
        // Applied, then changed after: what is in effect is not what was derived.
        val (laterWarn, later) = checkNotNull(
            IdentityHeaderLimit.line(environment, DataSize.ofKilobytes(12), DataSize.ofKilobytes(16))
        )
        laterWarn.assert().isTrue()
        later.assert().contains("12288 bytes, not derived")
        // Larger than derived, set elsewhere: named, not at WARN.
        val (largerWarn, larger) = checkNotNull(IdentityHeaderLimit.line(environment, DataSize.ofKilobytes(32), null))
        largerWarn.assert().isFalse()
        larger.assert().contains("32768 bytes, not derived").doesNotContain("set server.max-http-request-header-size")
        // No web server properties: no server to limit, and nothing said.
        IdentityHeaderLimit.line(environment, null, null).assert().isNull()
    }

    @Test
    fun `a host that reads the identity relays its callers within what it reads, or does not start - IDN-43`() {
        IdentityHeaderLimit.requireRelayWithinReads(environment(GATEWAY))
        val small = environment(GATEWAY, "cosec.pipeline.identity.max-size" to "4KB")
        assertThrownBy<IllegalStateException> { IdentityHeaderLimit.requireRelayWithinReads(small) }
            .hasMessageContaining("4096 bytes, below the 6144 bytes HttpControlService")
        // A host that does not read the identity has no R of its own to hold the relay to.
        IdentityHeaderLimit.requireRelayWithinReads(environment("cosec.pipeline.identity.max-size" to "4KB"))
    }

    @Test
    fun `a gateway writer limit above what every 8x service reads by default is named with both numbers - IDN-43`() {
        IdentityHeaderLimit.gatewayWriterLine(DataSize.ofKilobytes(6)).assert().isNull()
        IdentityHeaderLimit.gatewayWriterLine(DataSize.ofKilobytes(8)).assert().isNull()
        IdentityHeaderLimit.gatewayWriterLine(DataSize.ofKilobytes(12)).assert()
            .contains("12288 bytes, above the 8192 bytes an 8.x service reads by default")
    }

    private companion object {
        val GATEWAY = PipelineProperties.MODE_KEY to "gateway"
    }
}
