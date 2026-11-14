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
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.support.SystemEnvironmentPropertySourceEnvironmentPostProcessor
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.core.io.ByteArrayResource
import java.net.URLClassLoader
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** A `cosec.*` key CoSec does not read fails startup, and the message links the upgrade page. */
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
            "cosec.key-prefix=b",
            // Read from the environment by the Kafka audit listener: declared in KafkaAuditProperties for the metadata.
            "cosec.audit.kafka.delivery=required",
        ).run { context -> context.startupFailure.assert().isNull() }
    }

    @Test
    fun `a 7_x key fails startup and the message links the upgrade page`() {
        runner.withPropertyValues(
            "cosec.authentication.enabled=false",
            "cosec.authentication.social.registration.github.client-id=id",
            "cosec.limiter.enabled=true",
        ).run { context ->
            val message = context.startupFailure!!.messages()
            message.assert().contains("CoSec does not read 3 cosec.* properties,")
            message.assert().contains("cosec.authentication.enabled")
            message.assert().contains("cosec.authentication.social.registration.github.client-id")
            message.assert().contains("cosec.limiter.enabled")
            message.assert().contains(UnknownProperties.DOCUMENTATION)
        }
    }

    /**
     * `SNP-9`: an environment written for the three prefixes `cosec.key-prefix` replaced must not start. Its token
     * revocations may lie under a prefix nothing reads now, and the tokens they revoked would be accepted again.
     */
    @Test
    fun `a key prefix cosec key-prefix replaced fails startup`() {
        for (
        key in listOf(
            "cosec.authorization.snapshot.key-prefix=b",
            "cosec.authorization.cache.key-prefix=c",
            "cosec.api-key.key-prefix=x:keys",
            "cosec.api-key.keyPrefix=x:keys",
        )
        ) {
            runner.withPropertyValues(key, "cosec.key-prefix=b").run { context ->
                val message = context.startupFailure!!.messages()
                message.assert().contains("CoSec does not read 1 cosec.* property,")
                message.assert().containsIgnoringCase(key.substringBefore('='))
                message.assert().contains(UnknownProperties.DOCUMENTATION)
            }
        }
    }

    /** `SNP-9`: in any spelling Spring would have bound, the variable a deployment set for the old prefix fails too. */
    @Test
    fun `a replaced key prefix in the environment fails startup`() {
        for (
        variable in listOf(
            "COSEC_AUTHORIZATION_SNAPSHOT_KEYPREFIX",
            "COSEC_AUTHORIZATION_SNAPSHOT_KEY_PREFIX",
            "COSEC_AUTHORIZATION_CACHE_KEYPREFIX",
            "COSEC_APIKEY_KEYPREFIX",
            "COSEC_API_KEY_KEY_PREFIX",
        )
        ) {
            runner.withEnvironment(mapOf(variable to "b")).run { context ->
                context.startupFailure!!.messages().assert().contains("CoSec does not read 1 cosec.* property,")
            }
        }
    }

    /** `SNP-9`: with a switch, a moved prefix could be ignored and the revocations under it left unread. */
    @Test
    fun `no property turns the check off`() {
        runner.withPropertyValues("cosec.unknown-properties=warn", "cosec.authorization.cache.key-prefix=c")
            .run { context ->
                val message = context.startupFailure!!.messages()
                message.assert().contains("cosec.unknown-properties")
                message.assert().contains("cosec.authorization.cache.key-prefix")
            }
        runner.withPropertyValues("cosec.enabled=false", "cosec.nothing=1").run { context ->
            context.startupFailure!!.messages().assert().contains("cosec.nothing")
        }
    }

    /** U11: a key the rename left behind would silently stop doing what it says. */
    @ParameterizedTest
    @ValueSource(
        strings = [
            "cosec.pipeline.gateway.max-age=30s",
            "cosec.pipeline.gateway.max-size=4KB",
            "cosec.audit.required.timeout=2s",
            "cosec.authorization.cache.token.local-max-age=10s",
            "cosec.authorization.cache.token.maximum-size=10",
        ],
    )
    fun `a renamed key fails startup`(key: String) {
        runner.withPropertyValues(key).run { context ->
            context.startupFailure!!.messages().assert().contains(key.substringBefore('='))
        }
    }

    @Test
    fun `the new names of the renamed keys are known`() {
        runner.withPropertyValues(
            "cosec.pipeline.identity.max-age=30s",
            "cosec.pipeline.identity.max-size=4KB",
            "cosec.observability.listener.required-timeout=2s",
            "cosec.authentication.revocation.cache.local-max-age=10s",
            "cosec.authentication.revocation.cache.maximum-size=10",
        ).run { context -> context.startupFailure.assert().isNull() }
    }

    /**
     * #1234: Spring binds `COSEC_AUTHORIZATION_SNAPSHOT_LOCALMAXAGE` to `cosec.authorization.snapshot.local-max-age` (dots
     * become underscores, dashes are dropped, upper case), so a deployment that sets its keys that way made no mistake.
     */
    @Test
    fun `an environment variable Spring binds to a key CoSec reads passes`() {
        val variables = mapOf(
            "COSEC_AUTHORIZATION_SNAPSHOT_LOCALMAXAGE" to "30s",
            "COSEC_PIPELINE_ALLOWQUERYTOKEN" to "true",
            "COSEC_PIPELINE_TIMEOUT" to "3s",
            // A list element: the container's name is dashed in the metadata, the variable's is not.
            "COSEC_AUTHORIZATION_SNAPSHOT_FAILCLOSED_TENANTS_0" to "acme",
        )
        runner.withEnvironment(variables).run { context ->
            context.startupFailure.assert().isNull()
            Binder.get(context.environment).bind("cosec.authorization.snapshot.local-max-age", Duration::class.java)
                .get().assert().isEqualTo(Duration.ofSeconds(30))
            Binder.get(context.environment).bind("cosec.pipeline.allow-query-token", Boolean::class.javaObjectType)
                .get().assert().isTrue()
        }
    }

    @Test
    fun `the spellings Spring binds in application properties pass`() {
        runner.withPropertyValues(
            "cosec.authorization.snapshot.localMaxAge=30s",
            "cosec.pipeline.allow_query_token=true",
            "cosec.authorization.snapshot.failClosed.tenants[0]=acme",
        ).run { context ->
            context.startupFailure.assert().isNull()
            Binder.get(context.environment).bind("cosec.authorization.snapshot.local-max-age", Duration::class.java)
                .get().assert().isEqualTo(Duration.ofSeconds(30))
        }
    }

    /**
     * The relaxed spelling is no way in for a misspelling: only what Spring's documented conversion makes of a key CoSec
     * reads is known. Spring's binder also tolerates `COSEC_..._LOCAL_MAX_AGE` for `local-max-age`, a legacy spelling its
     * documentation does not offer; the check refuses it and names the key it read (`local.max.age`).
     */
    @Test
    fun `a variable that is not the documented spelling of a key CoSec reads still fails`() {
        val typos = mapOf(
            "COSEC_AUTHORIZATION_SNAPSHOT_LOCALMAXAG" to "30s",
            "COSEC_PIPELINE_TIMEUOT" to "3s",
            "COSEC_PIPELINE_ALLOWQUERYTOKENS" to "true",
            // A known key is no container: nothing is below it.
            "COSEC_PIPELINE_TIMEOUT_EXTRA" to "1",
        )
        runner.withEnvironment(typos + ("COSEC_AUTHORIZATION_SNAPSHOT_LOCAL_MAX_AGE" to "30s")).run { context ->
            val message = context.startupFailure!!.messages()
            message.assert().contains("CoSec does not read 5 cosec.* properties,")
            message.assert().contains("cosec.authorization.snapshot.local.max.age")
        }
        // Spring agrees about the typos: none of them reaches the key it was meant for.
        val environment = StandardEnvironment()
        environment.propertySources.addFirst(
            SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, typos)
        )
        Binder.get(environment).bind("cosec.authorization.snapshot.local-max-age", Duration::class.java)
            .isBound.assert().isFalse()
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

    /**
     * Review L4 of #1200: any jar may carry hand-written Spring metadata, which is not a format CoSec defines, so a
     * duplicate key in it is read, the last one winning, rather than failing CoSec's startup.
     */
    @Test
    fun `third-party metadata with a duplicate key is read`(@TempDir directory: Path) {
        val metadata = directory.resolve("META-INF").createDirectories()
            .resolve("additional-spring-configuration-metadata.json")
        metadata.writeText(
            """{"properties": [{"name": "cosec.third-party.flag", "type": "java.util.Map", "type": "java.lang.Boolean"}]}"""
        )
        val loader = URLClassLoader(arrayOf(directory.toUri().toURL()), null)
        val environment = StandardEnvironment()
        environment.propertySources.addFirst(MapPropertySource("test", mapOf("cosec.third-party.flag" to "true")))
        UnknownProperties.load(loader).of(environment).assert().isEmpty()
        // The last `type` won: the property is no map, so a key under it is unknown.
        environment.propertySources.addFirst(MapPropertySource("more", mapOf("cosec.third-party.flag.x" to "1")))
        UnknownProperties.load(loader).of(environment).assert().containsExactly("cosec.third-party.flag.x")
    }

    /** `STA-3`: under lazy initialization, as with `spring.main.lazy-initialization=true`, the check still runs. */
    @Test
    fun `an unknown key fails startup lazily too`() {
        runner.withPropertyValues("cosec.pipeline.timeuot=3s").lazily().run { context ->
            context.startupFailure!!.messages().assert().contains("cosec.pipeline.timeuot")
        }
    }

    /** The real `SpringApplication` path: the property itself, not an initializer standing in for it. */
    @Test
    fun `an unknown key fails a lazily initialized application`() {
        val failure = org.junit.jupiter.api.assertThrows<Exception> {
            org.springframework.boot.builder.SpringApplicationBuilder(Checked::class.java)
                .web(org.springframework.boot.WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off", "spring.main.lazy-initialization=true", "cosec.jwt.secrt=x")
                .run().close()
        }
        failure.messages().assert().contains("cosec.jwt.secrt")
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.ImportAutoConfiguration(CoSecPropertyCheckAutoConfiguration::class)
    class Checked
}
