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

import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cosec.jwt.JwtSigning
import me.ahoo.cosec.login.token.TokenConverter
import me.ahoo.cosec.testkit.IdentityHeader
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups
import org.springframework.context.ConfigurableApplicationContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The image as it ships, started on the in-memory CoCache backend with ephemeral ports (what `aot-train.sh` runs):
 * `application.yaml`, the starter's wiring and the image's own checks together. The statement is the one key the host
 * will not start without (`STA-9`). A call with an identity header, the 200 and the 403, is `aot-smoke.sh`'s: it runs
 * on the image with a platform document published through `cosec-migrate`, which the in-memory backend cannot do.
 */
@OptIn(me.ahoo.cosec.annotations.ExperimentalCoSecApi::class)
class ControlServerTest {
    private val http = HttpClient.newHttpClient()
    private var context: ConfigurableApplicationContext? = null

    @AfterEach
    fun close() {
        context?.close()
    }

    private fun start(vararg arguments: String): ConfigurableApplicationContext =
        SpringApplicationBuilder(ControlServer::class.java)
            .web(WebApplicationType.REACTIVE)
            .bannerMode(org.springframework.boot.Banner.Mode.OFF)
            .initializers(
                org.springframework.context.ApplicationContextInitializer<ConfigurableApplicationContext> { context ->
                    context.beanFactory.registerSingleton(
                        "inMemoryCoherenceBackend",
                        InMemoryBackend() as CoherenceBackend
                    )
                }
            )
            .run(
                // One address for the binds and the requests (#1962): bound to every address on a port the system chose,
                // a server shares that port with any process bound to one address on it, which then takes its connections.
                "--server.address=127.0.0.1",
                "--server.port=0",
                "--cosec.control.http.service-port=0",
                "--management.server.address=127.0.0.1",
                "--management.server.port=0",
                "--cocache.backend=memory",
                "--cosec.authorization.snapshot.eviction-check=warn",
                "--cosec.authorization.routes.location=",
                *arguments,
            ).also { context = it }

    private fun failure(vararg arguments: String): String {
        val message = runCatching { start(*arguments) }.exceptionOrNull()
        message.assert().describedAs("the image started").isNotNull()
        return generateSequence(message) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
    }

    private fun get(context: ConfigurableApplicationContext, port: String, path: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(
            URI("http://127.0.0.1:${context.environment.getProperty(port)}$path")
        ).build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `it does not start until the operator states what can reach it`() {
        val message = failure()
        message.assert().contains("cosec.pipeline.identity.only-trusted-callers-can-reach=true")
            .contains("cosec.pipeline.identity.only-gateway-can-reach=true").contains("STA-9")
    }

    @Test
    fun `it starts under the statement, with the control plane wired and nothing that verifies a credential`() {
        val context = start("--cosec.pipeline.identity.only-trusted-callers-can-reach=true")
        // The gate that believes the identity header, and the control plane's own store-format indicator.
        context.containsBean("cosecIdentityGate").assert().isTrue()
        context.containsBean("cosecControlStoreFormatHealthIndicator").assert().isTrue()
        // No signing secret, no token issuer, no login routes: the starter's JWT wiring is off.
        context.getBeanNamesForType(JwtSigning::class.java).assert().isEmpty()
        context.getBeanNamesForType(TokenConverter::class.java).assert().isEmpty()
    }

    @Test
    fun `a call with no identity is refused, and a credential is not an identity`() {
        val context = start("--cosec.pipeline.identity.only-trusted-callers-can-reach=true")
        val path = "/cosec/control/v1/documents/platform"
        get(context, "local.server.port", path).statusCode().assert().isEqualTo(401)
        // A bearer token nobody verifies is no more than the missing header: the host believes the identity, not this.
        val request = HttpRequest.newBuilder(
            URI("http://127.0.0.1:${context.environment.getProperty("local.server.port")}$path")
        )
            .header("Authorization", "Bearer not-a-token").build()
        http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode().assert().isEqualTo(401)
    }

    @Test
    fun `the probes are on the management port, and the control store's format joins the readiness group`() {
        val context = start("--cosec.pipeline.identity.only-trusted-callers-can-reach=true")
        get(context, "local.management.port", "/actuator/health/liveness").statusCode().assert().isEqualTo(200)
        // The document is not installed yet: unready, with the reason where the kubelet can read it.
        get(context, "local.management.port", "/actuator/health/readiness").statusCode().assert().isEqualTo(503)
        val groups = context.getBean(HealthEndpointGroups::class.java)
        groups.get("readiness")!!.isMember("cosecControlStoreFormat").assert().isTrue()
        // The scrape endpoint is on the management port only.
        get(context, "local.management.port", "/actuator/prometheus").statusCode().assert().isEqualTo(200)
        get(context, "local.server.port", "/actuator/prometheus").statusCode().assert().isNotEqualTo(200)
    }

    /**
     * Only the shadow reporter writes an instance's status (`InstanceStatus`), and it exists only with shadow on: a control
     * server writes none, so a shadow window never waits for it and `propagation` never counts it.
     */
    @Test
    fun `the service port serves the control API's service profile only, beside the operators' port`() {
        val context = start("--cosec.pipeline.identity.only-trusted-callers-can-reach=true")
        val header = IdentityHeader.forSubject("plato", tenant = "(platform)", app = "cosec-control")
        fun status(port: String, path: String): Int {
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${context.environment.getProperty(port)}$path"))
                .header(IdentityHeader.NAME, header).build()
            return http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()
        }
        val platform = "/cosec/control/v1/documents/platform"
        val tenant = "/cosec/control/v1/documents/tenant:acme"
        // The platform document is the operators', whoever asks: a 404 on the service port, answered on the other.
        status("local.control.service.port", platform).assert().isEqualTo(404)
        status("local.server.port", platform).assert().isNotEqualTo(404)
        // A tenant's document is in the service profile; what it answers is the control plane's own business.
        status("local.control.service.port", tenant).assert().isNotEqualTo(404)
        // Nothing but the control API is served on the service port.
        status("local.control.service.port", "/anything").assert().isEqualTo(404)
    }

    @Test
    fun `the gateway's statement beside the service port is refused, naming the one that says services can reach it`() {
        failure("--cosec.pipeline.identity.only-gateway-can-reach=true").assert()
            .contains("only-trusted-callers-can-reach=true").contains("cosec.control.http.service-port")
    }

    @Test
    fun `a control server writes no instance status, and takes no part in a shadow window`() {
        val statement = "--cosec.pipeline.identity.only-trusted-callers-can-reach=true"
        val server = start(statement)
        server.containsBean("cosecShadowReporting").assert().isFalse()
        server.containsBean("cosecShadowListener").assert().isFalse()
        server.close()
        // The same names are there when shadow is switched on, so the absence above is the switch's and not a rename's.
        val shadowed = start(statement, "--cosec.authorization.shadow.enabled=true")
        shadowed.containsBean("cosecShadowReporting").assert().isTrue()
        shadowed.containsBean("cosecShadowListener").assert().isTrue()
    }

    @Test
    fun `credential verification is refused whichever way it is asked for`() {
        for (key in listOf("cosec.jwt.enabled", "cosec.api-key.enabled", "cosec.authentication.login.enabled")) {
            failure("--cosec.pipeline.identity.only-trusted-callers-can-reach=true", "--$key=true").assert()
                .contains("verifies no credential").contains(key)
        }
        failure(
            "--cosec.pipeline.identity.only-trusted-callers-can-reach=true",
            "--cosec.control.enabled=false"
        ).assert()
            .contains("runs the control plane")
    }
}
