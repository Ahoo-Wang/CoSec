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

package me.ahoo.cosec.spring.boot.starter

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.SystemEnvironmentPropertySource

/** Exactly the variables Kubernetes writes for a Service named `cosec` or `cosec-*` are service links. */
class KubernetesServiceLinksTest {
    @ParameterizedTest
    @CsvSource(
        "COSEC_GATEWAY_SERVICE_HOST, 10.96.12.34",
        "COSEC_GATEWAY_SERVICE_PORT, 80",
        "COSEC_GATEWAY_SERVICE_PORT_HTTP, 80",
        "COSEC_GATEWAY_SERVICE_PORT_METRICS_UDP, 9100",
        "COSEC_GATEWAY_PORT, tcp://10.96.12.34:80",
        "COSEC_GATEWAY_PORT, udp://10.96.12.34:53",
        "COSEC_GATEWAY_PORT, sctp://10.96.12.34:9999",
        "COSEC_GATEWAY_PORT_80_TCP, tcp://10.96.12.34:80",
        "COSEC_GATEWAY_PORT_80_TCP_PROTO, tcp",
        "COSEC_GATEWAY_PORT_80_TCP_PORT, 80",
        "COSEC_GATEWAY_PORT_80_TCP_ADDR, 10.96.12.34",
        "COSEC_GATEWAY_PORT_53_UDP_ADDR, 10.96.12.34",
        "COSEC_GATEWAY_PORT_9999_SCTP_PROTO, sctp",
        "COSEC_GATEWAY_V2_SERVICE_HOST, 10.96.12.34",
        "COSEC_SERVICE_HOST, 10.96.12.34",
        "COSEC_PORT, tcp://10.96.12.34:80",
        "COSEC_PORT_80_TCP_ADDR, 10.96.12.34",
    )
    fun `a service link`(name: String, value: String) {
        KubernetesServiceLinks.isServiceLink(name, value).assert().isTrue()
    }

    @ParameterizedTest
    @CsvSource(
        // A typo, and a property written as a variable.
        "COSEC_JWT_SECRT, x",
        "COSEC_AUTHORIZATION_SNAPSHOT_FAIL_CLOSED_TENANTS, acme",
        // A port number is not a link URL: it is a mistyped cosec.<x>.port.
        "COSEC_GATEWAY_PORT, 8080",
        "COSEC_GATEWAY_PORT, http://10.96.12.34:80",
        "COSEC_GATEWAY_PORT, ''",
        // Shapes close to a service link.
        "COSEC_GATEWAY_SERVICE_HOSTNAME, x",
        "COSEC_GATEWAY_SERVICE, x",
        "COSEC_GATEWAY_PORT_80_HTTP, tcp://10.96.12.34:80",
        "COSEC_GATEWAY_PORT_HTTP_TCP, tcp://10.96.12.34:80",
        "COSEC_GATEWAY_PORT_80_TCP_HOST, 10.96.12.34",
        "COSEC_GATEWAY_PORT_80_TCP_PORT_X, 80",
        "COSEC_GATEWAYSERVICE_HOST, 10.96.12.34",
        "XCOSEC_GATEWAY_SERVICE_HOST, 10.96.12.34",
        // Kubernetes writes upper case.
        "cosec_gateway_service_host, 10.96.12.34",
        "cosec.gateway.service.host, 10.96.12.34",
        "Cosec_Gateway_Service_Host, 10.96.12.34",
        // Services of other names are not cosec.* keys at all.
        "REDIS_SERVICE_HOST, 10.96.12.34",
    )
    fun `not a service link`(name: String, value: String) {
        KubernetesServiceLinks.isServiceLink(name, value).assert().isFalse()
    }

    @Test
    fun `only the process environment loses its service links`() {
        val variables = mapOf("COSEC_GATEWAY_SERVICE_HOST" to "10.96.12.34", "COSEC_JWT_SECRT" to "x", "HOME" to "/")

        val environment = SystemEnvironmentPropertySource("systemEnvironment", variables)
        KubernetesServiceLinks.withoutServiceLinks(environment).assert()
            .isInstanceOf(SystemEnvironmentPropertySource::class.java)
        (KubernetesServiceLinks.withoutServiceLinks(environment) as SystemEnvironmentPropertySource)
            .propertyNames.toSet().assert().isEqualTo(setOf("COSEC_JWT_SECRT", "HOME"))

        val prefixed = SystemEnvironmentPropertySource("bootstrap-systemEnvironment", variables)
        (KubernetesServiceLinks.withoutServiceLinks(prefixed) as SystemEnvironmentPropertySource)
            .propertyNames.toSet().assert().isEqualTo(setOf("COSEC_JWT_SECRT", "HOME"))

        val other = SystemEnvironmentPropertySource("other", variables)
        KubernetesServiceLinks.withoutServiceLinks(other).assert().isSameAs(other)
        val map = MapPropertySource("systemEnvironment", variables)
        KubernetesServiceLinks.withoutServiceLinks(map).assert().isSameAs(map)
    }
}
