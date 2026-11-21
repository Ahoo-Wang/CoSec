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

package me.ahoo.cosec.spring.boot.realserver

import io.netty.channel.group.DefaultChannelGroup
import io.netty.util.concurrent.GlobalEventExecutor
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spring.boot.control.ControlServiceServer
import me.ahoo.cosec.spring.boot.pipeline.GATEWAY_GLOBAL_FILTER
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.DefaultResourceLoader
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.time.Duration
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * `CTL-71`: the control API's service port is the application's own server on another port, so it has the application's
 * server settings and no more of its own. On real servers: with `server.ssl.*` and `server.ssl.client-auth=need`, a request
 * in clear text to the service port fails and so does a client with no certificate, as on the application port, and one with
 * a certificate is answered; with `server.address=127.0.0.1` the service port is not reachable on another address; and the
 * factory it is built from has the settings of the application's factory.
 *
 * A bare second Netty server took none of them: an operator who makes `only-trusted-callers-can-reach` true by mutual TLS on
 * the application's port got clear text on every address that believed any `CoSec-Identity`.
 */
class ControlServicePortSettingsTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    class Application

    @Test
    fun `with mutual TLS on the application port the service port is TLS with a client certificate too`(
        @TempDir dir: Path
    ) {
        val store = keystore(dir)
        start(
            "server.ssl.key-store=$store",
            "server.ssl.key-store-password=$PASSWORD",
            "server.ssl.key-store-type=PKCS12",
            "server.ssl.trust-store=$store",
            "server.ssl.trust-store-password=$PASSWORD",
            "server.ssl.trust-store-type=PKCS12",
            "server.ssl.client-auth=need",
        ).use { context ->
            val applicationPort = context.environment.getRequiredProperty("local.server.port").toInt()
            val service = context.environment.getRequiredProperty("local.control.service.port").toInt()
            for ((name, port) in listOf("application" to applicationPort, "service" to service)) {
                // In clear text: the port speaks TLS, so nothing answers.
                fails { client(null).send(request("http", port), HttpResponse.BodyHandlers.discarding()) }
                    .assert().describedAs("clear text to the $name port").isTrue()
                // TLS, trusting the server, with no certificate of its own: refused as the server demands one.
                fails { client(trusting(store)).send(request("https", port), HttpResponse.BodyHandlers.discarding()) }
                    .assert().describedAs("no client certificate to the $name port").isTrue()
                // TLS with a certificate: answered, whatever the control plane answers.
                val answer = client(mutual(store)).send(request("https", port), HttpResponse.BodyHandlers.discarding())
                answer.statusCode().assert().describedAs("a client certificate to the $name port").isPositive()
            }
            // The factory the service port's server is built from has what the application's has.
            val own = ControlServiceServer.factory(context, 0, DefaultChannelGroup(GlobalEventExecutor.INSTANCE))
            val application = context.getBean(NettyReactiveWebServerFactory::class.java)
            val ssl = checkNotNull(own.ssl) { "the service port's server has no TLS settings" }
            ssl.clientAuth.assert().isEqualTo(checkNotNull(application.ssl).clientAuth)
        }
    }

    @Test
    fun `with a bind address on the application port the service port listens there only`() {
        val other = otherAddress()
        assumeTrue(other != null, "this machine has an address besides the loopback one")
        start("server.address=127.0.0.1").use { context ->
            val service = context.environment.getRequiredProperty("local.control.service.port").toInt()
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", service), CONNECT_MILLIS) }
            fails { Socket().use { it.connect(InetSocketAddress(other, service), CONNECT_MILLIS) } }
                .assert().describedAs("the service port on $other").isTrue()
            val own = ControlServiceServer.factory(context, 0, DefaultChannelGroup(GlobalEventExecutor.INSTANCE))
            own.address.assert().isEqualTo(InetAddress.getByName("127.0.0.1"))
        }
    }

    private fun start(vararg settings: String): ConfigurableApplicationContext {
        val hidden = listOf(
            GATEWAY_GLOBAL_FILTER,
            "org.eclipse.jetty",
            "io.undertow",
            "org.apache.catalina",
            "org.apache.tomcat",
            "org.apache.coyote",
            "me.ahoo.cosec.kafka",
            "me.ahoo.cosec.social",
            "me.ahoo.cosec.openapi",
            "me.ahoo.cosec.cosid",
            "me.ahoo.cosec.opentelemetry",
            "me.ahoo.cosec.micrometer",
            "me.ahoo.cosec.ip2region",
        )
        val properties = listOf(
            "server.port=0",
            "cosec.control.http.service-port=0",
            "cocache.backend=memory",
            "cosec.jwt.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
            "cosec.authorization.routes.location=",
            "spring.main.banner-mode=off",
            "spring.autoconfigure.exclude=" + gatewayConfigurations().joinToString(","),
        ) + settings
        return SpringApplicationBuilder(Application::class.java)
            .web(WebApplicationType.REACTIVE)
            .resourceLoader(DefaultResourceLoader(FilteredClassLoader(*hidden.toTypedArray())))
            .properties(*properties.toTypedArray())
            .run()
    }

    private fun gatewayConfigurations(): List<String> =
        ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).asSequence()
            .filter { it.startsWith("org.springframework.cloud.gateway.") }
            .toList()

    /** Whether [call] failed to get an answer: refused, reset or not understood. */
    private fun fails(call: () -> Unit): Boolean = try {
        call()
        false
    } catch (@Suppress("SwallowedException") e: IOException) {
        true
    }

    private fun request(scheme: String, port: Int): HttpRequest =
        HttpRequest.newBuilder(URI.create("$scheme://localhost:$port/cosec/control/v1/documents/tenant:acme"))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS)).GET().build()

    private fun client(context: SSLContext?): HttpClient =
        HttpClient.newBuilder().apply {
            context?.let(
                ::sslContext
            )
        }.connectTimeout(Duration.ofSeconds(TIMEOUT_SECONDS)).build()

    private fun otherAddress(): InetAddress? = NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.asSequence() }
        .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }

    /** A self-signed certificate for `localhost`, in a PKCS12 file of [dir]: what `server.ssl.key-store` reads. */
    private fun keystore(dir: Path): Path {
        val file = dir.resolve("cosec.p12")
        val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
        val process = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "cosec", "-keyalg", "RSA", "-keysize", "2048", "-storetype", "PKCS12",
            "-keystore", file.toString(), "-storepass", PASSWORD, "-keypass", PASSWORD, "-dname", "CN=localhost",
            "-validity", "2", "-ext", "san=dns:localhost,ip:127.0.0.1",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "keytool failed: $output" }
        return file
    }

    private fun load(store: Path): KeyStore = KeyStore.getInstance("PKCS12").apply {
        Files.newInputStream(store).use { load(it, PASSWORD.toCharArray()) }
    }

    /** A client that trusts the server's certificate and presents none. */
    private fun trusting(store: Path): SSLContext = SSLContext.getInstance("TLS").apply {
        val trust = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        ).apply { init(load(store)) }
        init(null, trust.trustManagers, null)
    }

    /** A client that trusts the server's certificate and presents it as its own. */
    private fun mutual(store: Path): SSLContext = SSLContext.getInstance("TLS").apply {
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(load(store), PASSWORD.toCharArray())
        }
        val trust = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm()
        ).apply { init(load(store)) }
        init(keys.keyManagers, trust.trustManagers, null)
    }

    private companion object {
        const val PASSWORD = "changeit"
        const val TIMEOUT_SECONDS = 10L
        const val CONNECT_MILLIS = 2_000
    }
}
