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

package me.ahoo.cosec.control.server

import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

/** `STA-10`: what the image refuses to start with, read from the environment before any bean exists. */
class ControlServerRefusalsTest {
    @Test
    fun `the image's own defaults, and nothing written, are accepted`() {
        ControlServerRefusals.check(MockEnvironment())
        ControlServerRefusals.check(
            MockEnvironment().withProperty("cosec.control.enabled", "true")
                .withProperty("cosec.jwt.enabled", "false").withProperty("cosec.api-key.enabled", "false")
        )
    }

    @Test
    fun `a control server with the control plane off is an empty application that is up, so it is refused`() {
        for (value in listOf("false", "FALSE", " false ")) {
            assertThrownBy<IllegalStateException> {
                ControlServerRefusals.check(MockEnvironment().withProperty("cosec.control.enabled", value))
            }.hasMessageContaining("cosec.control.enabled=false").hasMessageContaining("gateway image")
                .hasMessageContaining("STA-10")
        }
    }

    /** DR1-F-19: what comes to the control plane off is refused as it is, by its own key. */
    @Test
    fun `settings that leave the control server serving nothing are refused like the control plane off`() {
        val refused = listOf(
            "cosec.enabled" to "false",
            "cosec.control.http.enabled" to " FALSE ",
            "cosec.pipeline.mode" to "gateway",
            "cosec.pipeline.mode" to "GATEWAY",
            "cosec.pipeline.caller" to "credential",
            "cosec.pipeline.caller" to "CREDENTIAL",
        )
        for ((key, value) in refused) {
            assertThrownBy<IllegalStateException> {
                ControlServerRefusals.check(MockEnvironment().withProperty(key, value))
            }.hasMessageContaining(key).hasMessageContaining("STA-10")
        }
        ControlServerRefusals.check(
            MockEnvironment().withProperty("cosec.enabled", "true").withProperty("cosec.control.http.enabled", "true")
                .withProperty("cosec.pipeline.mode", "enforce").withProperty("cosec.pipeline.caller", "forwarded-identity")
        )
        // A value the starter cannot bind is the starter's to refuse.
        ControlServerRefusals.check(MockEnvironment().withProperty("cosec.pipeline.mode", "sideways"))
    }

    @Test
    fun `credential verification is refused because the host believes the identity it is sent`() {
        for (key in listOf("cosec.jwt.enabled", "cosec.api-key.enabled", "cosec.authentication.login.enabled")) {
            for (value in listOf("true", "TRUE", " true ")) {
                assertThrownBy<IllegalStateException> {
                    ControlServerRefusals.check(MockEnvironment().withProperty(key, value))
                }.hasMessageContaining("$key=true").hasMessageContaining("verifies no credential")
                    .hasMessageContaining("STA-10")
            }
        }
    }

    @Test
    fun `a value that is neither true nor false is the starter's to refuse, not this check's`() {
        ControlServerRefusals.check(MockEnvironment().withProperty("cosec.jwt.enabled", "on"))
        ControlServerRefusals.check(MockEnvironment().withProperty("cosec.control.enabled", "maybe"))
    }
}
