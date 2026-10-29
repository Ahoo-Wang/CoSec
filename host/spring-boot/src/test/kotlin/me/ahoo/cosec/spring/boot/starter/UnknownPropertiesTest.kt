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
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.support.SystemEnvironmentPropertySourceEnvironmentPostProcessor
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.ByteArrayResource

/** A `cosec.*` key CoSec does not read fails startup, with what 8.0 replaced it with when it was a 7.x key. */
class UnknownPropertiesTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoSecPropertyCheckAutoConfiguration::class.java))

    private fun Throwable.messages() = generateSequence(this) { it.cause }.joinToString(" | ") { it.message.orEmpty() }

    /** [variables] as a process environment, in place of the real one: these tests never read the real one. */
    private fun ApplicationContextRunner.withEnvironment(
        variables: Map<String, String>,
        sourceName: String = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
    ) = withInitializer { context ->
        context.environment.propertySources.addFirst(SystemEnvironmentPropertySource(sourceName, variables))
    }

    @Test
    fun `an unknown key fails startup`() {
        runner.withPropertyValues("cosec.pipeline.timeuot=3s").run { context ->
            context.startupFailure!!.messages().assert().contains("cosec.pipeline.timeuot")
        }
    }

    @Test
    fun `keys CoSec reads pass, maps and lists included`() {
        runner.withPropertyValues(
            "cosec.enabled=true",
            "cosec.pipeline.timeout=3s",
            "cosec.authentication.login.enabled=false",
            "cosec.authorization.snapshot.fail-closed.tenants[0]=acme",
            "cosec.authentication.login.social.registration.github.client-id=id",
            "cosec.unknown-properties=fail",
            // Read from the environment by the Kafka audit listener: declared in KafkaAuditProperties for the metadata.
            "cosec.audit.kafka.delivery=required",
        ).run { context -> context.startupFailure.assert().isNull() }
    }

    @Test
    fun `a 7_x key names what replaced it`() {
        runner.withPropertyValues(
            "cosec.authentication.enabled=false",
            "cosec.authentication.social.registration.github.client-id=id",
            "cosec.limiter.enabled=true",
            "cosec.authorization.cache.token.expire-after-write=30",
            "cosec.authorization.cache.token.expire-unit=SECONDS",
        ).run { context ->
            val message = context.startupFailure!!.messages()
            message.assert().contains("cosec.authentication.enabled -> cosec.authentication.login.enabled")
            message.assert().contains(
                "cosec.authentication.social.registration.github.client-id -> cosec.authentication.login.social."
            )
            message.assert().contains("cosec.limiter.enabled -> removed")
            message.assert().contains(
                "cosec.authorization.cache.token.expire-after-write -> cosec.authorization.cache.token.local-max-age"
            )
            message.assert().contains("cosec.authorization.cache.token.expire-unit -> removed")
        }
    }

    @Test
    fun `warn only logs`() {
        runner.withPropertyValues("cosec.unknown-properties=warn", "cosec.nothing=1").run { context ->
            context.startupFailure.assert().isNull()
        }
    }

    // What `kubectl exec <pod> -- env` shows in a namespace with this Service (ClusterIP, port 80/TCP named http):
    //   metadata.name: cosec-gateway
    //   spec.ports: [{name: http, port: 80, protocol: TCP}]
    private val gatewayServiceLinks = mapOf(
        "COSEC_GATEWAY_SERVICE_HOST" to "10.96.12.34",
        "COSEC_GATEWAY_SERVICE_PORT" to "80",
        "COSEC_GATEWAY_SERVICE_PORT_HTTP" to "80",
        "COSEC_GATEWAY_PORT" to "tcp://10.96.12.34:80",
        "COSEC_GATEWAY_PORT_80_TCP" to "tcp://10.96.12.34:80",
        "COSEC_GATEWAY_PORT_80_TCP_PROTO" to "tcp",
        "COSEC_GATEWAY_PORT_80_TCP_PORT" to "80",
        "COSEC_GATEWAY_PORT_80_TCP_ADDR" to "10.96.12.34",
    )

    @Test
    fun `the service links of a Service named cosec-gateway do not fail startup`() {
        runner.withEnvironment(gatewayServiceLinks).run { context -> context.startupFailure.assert().isNull() }
    }

    @Test
    fun `the service links of a multi-port Service named cosec do not fail startup`() {
        // A Service named cosec has no suffix in its variable names; each port has a protocol of its own and an IPv6
        // address is possible. Only the first port gets the unsuffixed COSEC_PORT.
        runner.withEnvironment(
            mapOf(
                "COSEC_SERVICE_HOST" to "fd00:10:96::1234",
                "COSEC_SERVICE_PORT" to "80",
                "COSEC_SERVICE_PORT_HTTP" to "80",
                "COSEC_SERVICE_PORT_DNS_UDP" to "5353",
                "COSEC_SERVICE_PORT_SCTP_PORT" to "9999",
                "COSEC_PORT" to "tcp://[fd00:10:96::1234]:80",
                "COSEC_PORT_80_TCP" to "tcp://[fd00:10:96::1234]:80",
                "COSEC_PORT_80_TCP_PROTO" to "tcp",
                "COSEC_PORT_80_TCP_PORT" to "80",
                "COSEC_PORT_80_TCP_ADDR" to "fd00:10:96::1234",
                "COSEC_PORT_5353_UDP" to "udp://[fd00:10:96::1234]:5353",
                "COSEC_PORT_5353_UDP_PROTO" to "udp",
                "COSEC_PORT_5353_UDP_PORT" to "5353",
                "COSEC_PORT_5353_UDP_ADDR" to "fd00:10:96::1234",
                "COSEC_PORT_9999_SCTP" to "sctp://[fd00:10:96::1234]:9999",
                "COSEC_PORT_9999_SCTP_PROTO" to "sctp",
                "COSEC_PORT_9999_SCTP_PORT" to "9999",
                "COSEC_PORT_9999_SCTP_ADDR" to "fd00:10:96::1234",
            )
        ).run { context -> context.startupFailure.assert().isNull() }
    }

    @Test
    fun `service links do not hide a mistyped variable next to them`() {
        runner.withEnvironment(gatewayServiceLinks + ("COSEC_JWT_SECRT" to "s3cret")).run { context ->
            val message = context.startupFailure!!.messages()
            message.assert().contains("CoSec does not read 1 cosec.* property,")
            message.assert().contains("cosec.jwt.secrt")
            message.assert().doesNotContain("cosec.gateway")
        }
    }

    @Test
    fun `service links from Spring Boot's origin-tracking environment source do not fail startup`() {
        runner.withInitializer { context ->
            val environment = context.environment
            environment.propertySources.addFirst(
                SystemEnvironmentPropertySource(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    gatewayServiceLinks,
                )
            )
            // What SpringApplication does to the environment before the context starts.
            SystemEnvironmentPropertySourceEnvironmentPostProcessor()
                .postProcessEnvironment(environment, SpringApplication())
            ConfigurationPropertySources.attach(environment)
        }.run { context -> context.startupFailure.assert().isNull() }
    }

    @Test
    fun `the same names in application yaml or system properties still fail`() {
        val yaml = ByteArrayResource(
            "cosec:\n  gateway:\n    service:\n      host: 10.96.12.34\n    port: tcp://10.96.12.34:80\n".toByteArray()
        )
        runner.withInitializer { context ->
            YamlPropertySourceLoader().load("application.yaml", yaml)
                .forEach { context.environment.propertySources.addLast(it) }
        }.run { context ->
            val message = context.startupFailure!!.messages()
            message.assert().contains("cosec.gateway.port")
            message.assert().contains("cosec.gateway.service.host")
        }
        runner.withSystemProperties("cosec.gateway.service.host=10.96.12.34").run { context ->
            context.startupFailure!!.messages().assert().contains("cosec.gateway.service.host")
        }
        runner.withPropertyValues("cosec.gateway.service.host=10.96.12.34").run { context ->
            context.startupFailure!!.messages().assert().contains("cosec.gateway.service.host")
        }
    }

    @Test
    fun `only the process environment is exempt`() {
        // Spring reads variable names only from a SystemEnvironmentPropertySource named systemEnvironment, or
        // <prefix>-systemEnvironment; from a source of another name the keys are the names as they stand.
        runner.withEnvironment(gatewayServiceLinks, "bootstrap-systemEnvironment").run { context ->
            context.startupFailure.assert().isNull()
        }
        runner.withEnvironment(
            mapOf("cosec.gateway.service.host" to "10.96.12.34", "COSEC_GATEWAY_SERVICE_PORT" to "80"),
            "custom",
        ).run { context ->
            context.startupFailure!!.messages().assert().contains("CoSec does not read 1 cosec.* property,")
        }
        // A source named like the environment that is not one.
        runner.withInitializer { context ->
            context.environment.propertySources.addFirst(
                MapPropertySource(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    mapOf("cosec.gateway.service.host" to "10.96.12.34"),
                )
            )
        }.run { context ->
            context.startupFailure!!.messages().assert().contains("cosec.gateway.service.host")
        }
    }

    @Test
    fun `variables that only look like service links still fail`() {
        runner.withEnvironment(
            mapOf(
                // A port number: a mistyped cosec.gateway.port, not the URL Kubernetes writes.
                "COSEC_GATEWAY_PORT" to "8080",
                "COSEC_PIPELINE_PORT_80_HTTP" to "tcp://10.96.12.34:80",
                "COSEC_PIPELINE_SERVICE_HOSTNAME" to "x",
                "COSEC_PIPELINE_SERVICE" to "x",
                // Kubernetes writes upper case: this is a property written as a variable.
                "cosec_pipeline_service_host" to "x",
            )
        ).run { context ->
            val message = context.startupFailure!!.messages()
            message.assert().contains("CoSec does not read 5 cosec.* properties,")
        }
    }
}
