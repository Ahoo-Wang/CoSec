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

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey
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
import org.springframework.boot.web.server.autoconfigure.ServerProperties
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.util.unit.DataSize
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse
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
    class Application {
        /**
         * An application's own route under the control API's base path, ordered before the control routes: the worst place
         * it can be, since a request for it would reach it first wherever the control API is served.
         */
        @Bean
        @Order(Ordered.HIGHEST_PRECEDENCE)
        fun applicationRouteUnderTheBase(): RouterFunction<ServerResponse> = RouterFunctions.route()
            .GET("$BASE/application-route") { ServerResponse.ok().bodyValue("application") }.build()
    }

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
            val own = ControlServiceServer.factory(context, 0)
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
            val own = ControlServiceServer.factory(context, 0)
            own.address.assert().isEqualTo(InetAddress.getByName("127.0.0.1"))
        }
    }

    /**
     * `IDN-43`, `CTL-71`: a host that reads the identity gets a request-header limit derived from what it reads
     * (`cosec.pipeline.identity.max-size` and 8 KB for the rest), and the service port, the application's server on
     * another port, has it too: a request whose headers pass Spring Boot's default 8 KB reaches the gate on either
     * port. A host that does not read the identity keeps the server's own default, which refuses that request.
     */
    @Test
    fun `a host that reads the identity derives its header limit, on the service port as on its own - IDN-43`() {
        withoutGateway {
            start(
                "cosec.pipeline.caller=forwarded-identity",
                "cosec.pipeline.identity.only-trusted-callers-can-reach=true",
                "cosec.jwt.enabled=false",
                "cosec.authorization.snapshot.eviction-check=warn",
            )
        }.use { context ->
            context.serverLimit().assert().isEqualTo(DataSize.ofKilobytes(16))
            val application = context.environment.getRequiredProperty("local.server.port").toInt()
            val service = context.environment.getRequiredProperty("local.control.service.port").toInt()
            for ((name, port) in listOf("application" to application, "service" to service)) {
                statusWithPadding(port, PADDING).assert().describedAs("the $name port")
                    .isEqualTo(statusWithPadding(port, 0))
            }
        }
        start().use { context ->
            context.serverLimit().assert().isEqualTo(DataSize.ofKilobytes(8))
            val service = context.environment.getRequiredProperty("local.control.service.port").toInt()
            statusWithPadding(service, PADDING).assert().isNotEqualTo(statusWithPadding(service, 0))
        }
    }

    private fun ConfigurableApplicationContext.serverLimit(): DataSize =
        getBean(ServerProperties::class.java).maxHttpRequestHeaderSize

    /**
     * [block] with Spring Cloud Gateway hidden from the thread's class loader too: the guard that refuses a host believing
     * the identity on a gateway looks the gateway up there, not through the application's resource loader.
     */
    private fun <T> withoutGateway(block: () -> T): T {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = FilteredClassLoader(GATEWAY_GLOBAL_FILTER)
        return try {
            block()
        } finally {
            thread.contextClassLoader = previous
        }
    }

    /** The status of a `GET` of the control API on [port], with a header of [padding] bytes besides. */
    private fun statusWithPadding(port: Int, padding: Int): Int = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = SOCKET_MILLIS
        socket.getOutputStream().apply {
            val request = "GET $BASE/documents/tenant:acme HTTP/1.1\r\nHost: localhost\r\n" +
                "X-Padding: ${"x".repeat(padding)}\r\nConnection: close\r\n\r\n"
            write(request.encodeToByteArray())
            flush()
        }
        val line = socket.getInputStream().bufferedReader().readLine()
        checkNotNull(line) { "the connection closed before a response" }.split(' ')[1].toInt()
    }

    @Test
    fun `the service port of a running application closes an idle keep-alive connection when the application stops`() {
        listOf("graceful", "immediate").forEach(::keepAliveClosedOnStop)
    }

    private fun keepAliveClosedOnStop(shutdown: String) {
        val context = start("server.shutdown=$shutdown")
        val service = context.environment.getRequiredProperty("local.control.service.port").toInt()
        Socket("127.0.0.1", service).use { socket ->
            socket.soTimeout = SOCKET_MILLIS
            socket.getOutputStream().apply {
                write("GET /anything HTTP/1.1\r\nHost: localhost\r\n\r\n".encodeToByteArray())
                flush()
            }
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) {
                val next = socket.getInputStream().read()
                check(next >= 0) { "the connection closed before a response: [$head]" }
                head.append(next.toChar())
            }
            repeat(Regex("(?i)content-length: *(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0) {
                socket.getInputStream().read()
            }
            // The connection is open and idle, as a service's pooled one is: stopping must not leave it standing.
            context.close()
            val closed = try {
                socket.getInputStream().read() == -1
            } catch (@Suppress("SwallowedException") e: java.net.SocketTimeoutException) {
                false
            }
            closed.assert().describedAs(
                "the idle keep-alive connection was closed when the application stopped"
            ).isTrue()
        }
    }

    @Test
    fun `the service port serves the control API's routes and no route of the application, under the base path or not`() {
        start().use { context ->
            val applicationPort = context.environment.getRequiredProperty("local.server.port").toInt()
            val service = context.environment.getRequiredProperty("local.control.service.port").toInt()
            publish(context)
            val route = "$BASE/application-route"
            // The application's route is its own on the application's port, once the platform document permits it...
            val deadline = System.nanoTime() + Duration.ofSeconds(DOCUMENT_SECONDS).toNanos()
            while (status(applicationPort, route) != OK) {
                check(System.nanoTime() < deadline) { "The platform document was not installed." }
                Thread.sleep(POLL_MILLIS)
            }
            // ...and on the service port nothing of the application answers, though the same document permits the path there
            // too: the control routes are what answers under the base path, and with no principal behind the request they say
            // 401, never what the application's handler would say.
            val served = get(service, route)
            served.second.assert().describedAs(
                "the application's route under the base path"
            ).isNotEqualTo("application")
            served.first.assert().isNotEqualTo(OK)
            // A route of the control API itself is served there (the gate answers it, whatever it answers).
            get(service, "$BASE/documents/tenant:acme").first.assert().isNotEqualTo(NOT_FOUND)
        }
    }

    private fun status(port: Int, path: String): Int = get(port, path).first

    /** The status and the body of a plain `GET`. */
    private fun get(port: Int, path: String): Pair<Int, String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS)).GET().build()
        val answer = CLIENT_PLAIN.send(request, HttpResponse.BodyHandlers.ofString())
        return answer.statusCode() to answer.body()
    }

    /** The platform document of this test: the application's route under the base path is permitted to anyone, nothing else. */
    private fun publish(context: ConfigurableApplicationContext) {
        val revisions = context.getBean(Revisions::class.java)
        kotlinx.coroutines.runBlocking {
            revisions.publish(
                SnapshotKey.Platform,
                revisions.head(SnapshotKey.Platform),
                PolicyDocumentCodec.decode(DocumentKind.PLATFORM, "", PLATFORM.encodeToByteArray()),
                Publication("bootstrap", ProvenanceKind.BOOTSTRAP, Actor(TenantId.PLATFORM, PrincipalId("plato"))),
            )
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
            // One address for the binds and the requests (#1962): bound to every address on a port the system chose, a
            // server shares that port with any process bound to one address on it, which then takes its connections.
            "server.address=127.0.0.1",
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
        HttpRequest.newBuilder(URI.create("$scheme://127.0.0.1:$port/cosec/control/v1/documents/tenant:acme"))
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

        /** Past Spring Boot's default 8 KB of request headers, within the 16 KB derived for a host reading identities. */
        const val PADDING = 10 * 1024
        const val TIMEOUT_SECONDS = 10L
        const val BASE = "/cosec/control/v1"
        const val OK = 200
        const val NOT_FOUND = 404
        const val DOCUMENT_SECONDS = 30L
        const val POLL_MILLIS = 50L
        val CLIENT_PLAIN: HttpClient = HttpClient.newBuilder().connectTimeout(
            Duration.ofSeconds(TIMEOUT_SECONDS)
        ).build()
        const val PLATFORM = """{"schema":2,"revision":1,"publishedAt":"2026-11-01T00:00:00Z",""" +
            """"policies":[{"id":"open","statements":[{"effect":"allow","action":"http:GET",""" +
            """"resource":"http:path:/cosec/control/v1/application-route"}]}],""" +
            """"bindings":[{"id":"open","subject":"*","app":"*","space":"*","policy":"open"}]}"""
        const val CONNECT_MILLIS = 2_000
        const val SOCKET_MILLIS = 5_000
    }
}
