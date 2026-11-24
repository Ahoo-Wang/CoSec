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

package me.ahoo.cosec.spring.boot.control

import io.mockk.mockk
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.api.ControlHttp
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.mock.env.MockEnvironment

/** The startup line of a host that runs the control plane: what it serves, and where its callers come from. */
class ControlPlaneNoticeTest {
    private val properties = ControlProperties()

    @Test
    fun `a control host that believes the identity, with a service port`() {
        val environment = MockEnvironment()
            .withProperty("cosec.pipeline.caller", "forwarded-identity")
            .withProperty("cosec.pipeline.identity.only-trusted-callers-can-reach", "true")
            .withProperty("cosec.control.http.service-port", "8082")
        ControlPlaneNotice.line(environment, properties).assert().isEqualTo(
            "Control plane: HTTP routes on /cosec/control (port 8080; service port 8082), " +
                "caller from the forwarded identity (statement: only-trusted-callers-can-reach)"
        )
    }

    @Test
    fun `an application that verifies credentials, on the port its server bound`() {
        val environment = MockEnvironment()
            .withProperty("server.port", "0")
            .withProperty("local.server.port", "40123")
        ControlPlaneNotice.line(environment, properties).assert()
            .isEqualTo("Control plane: HTTP routes on /cosec/control (port 40123), caller from the credential")
    }

    @Test
    fun `an embedding service that reaches the control plane in process only`() {
        val environment = MockEnvironment()
            .withProperty("cosec.control.http.enabled", "false")
            .withProperty("cosec.pipeline.caller", "forwarded-identity")
            .withProperty("cosec.pipeline.identity.only-gateway-can-reach", "true")
        ControlPlaneNotice.line(environment, properties).assert().isEqualTo(
            "Control plane: no HTTP routes (cosec.control.http.enabled=false), " +
                "caller from the forwarded identity (statement: only-gateway-can-reach)"
        )
    }

    @Test
    fun `the base path and the service port the listener bound are the ones said`() {
        val moved = ControlProperties().apply { http.basePath = "/admin/policies/" }
        val environment = MockEnvironment()
            .withProperty("server.port", "9000")
            .withProperty("cosec.control.http.service-port", "0")
            .withProperty("local.control.service.port", "40999")
        ControlPlaneNotice.line(environment, moved).assert()
            .isEqualTo(
                "Control plane: HTTP routes on /admin/policies (port 9000; service port 40999), caller from the credential"
            )
    }

    /** DR1-G-5: the classes and the switch do not say the control plane runs; a host in gateway mode wires none. */
    @Test
    @ExtendWith(OutputCaptureExtension::class)
    fun `the line is said only where the control plane is wired`(output: CapturedOutput) {
        val beans = DefaultListableBeanFactory()
        val notice = CoSecControlServicePortAutoConfiguration()
            .cosecControlPlaneNotice(MockEnvironment(), properties, beans.getBeanProvider(ControlHttp::class.java))
        val ready = mockk<ApplicationReadyEvent>()
        notice.onApplicationEvent(ready)
        output.out.contains("Control plane: HTTP routes").assert().isFalse()
        beans.registerSingleton("cosecControlHttp", mockk<ControlHttp>())
        notice.onApplicationEvent(ready)
        output.out.contains("Control plane: HTTP routes on /cosec/control").assert().isTrue()
    }
}
