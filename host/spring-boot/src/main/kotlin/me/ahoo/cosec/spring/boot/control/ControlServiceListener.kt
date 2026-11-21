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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.InternalCoSecApi
import org.springframework.boot.web.server.WebServer
import org.springframework.context.ApplicationContext
import org.springframework.context.SmartLifecycle
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import org.springframework.http.server.reactive.HttpHandler
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

private val log = KotlinLogging.logger {}

/**
 * The service port (`cosec.control.http.service-port`, `CTL-58`): a second Reactor Netty server over the same
 * [HttpHandler] as the application's, so the same filters and the same gate decide its requests, and a listener of its
 * own for the network to admit services to. It is the application's own server on another port ([ControlServiceServer],
 * `CTL-71`): its TLS, bind address, HTTP/2, limits and timeouts are the application's. WebFlux only. Which requests are the service profile's is told by the
 * local port ([boundPort]): see [ServicePortFilter] and the control routes.
 *
 * It stops as it started, on its own: unbinding first, so no new connection arrives; then waiting up to [grace] for the
 * requests in flight; then closing every connection still open, the idle keep-alive ones included, which `disposeNow`
 * leaves standing (a caller holding one would keep being served by a host that is shutting down).
 */
internal class ControlServiceListener(
    private val context: ApplicationContext,
    private val port: Int,
    private val grace: Duration,
) : SmartLifecycle {
    private val inFlight = AtomicInteger()

    @Volatile
    private var server: WebServer? = null

    /** The port it listens on; 0 while it is not started. */
    @Volatile
    var boundPort: Int = 0
        private set

    override fun start() {
        val handler = context.getBean(HttpHandler::class.java)
        val counted = HttpHandler { request, response ->
            inFlight.incrementAndGet()
            handler.handle(request, response).doFinally { inFlight.decrementAndGet() }
        }
        val bound = ControlServiceServer.factory(context, port).getWebServer(counted)
        bound.start()
        server = bound
        boundPort = bound.port
        // As the server and management ports are announced (`local.server.port`), for the tests and tools that bind to 0.
        (context.environment as? ConfigurableEnvironment)?.propertySources
            ?.addFirst(MapPropertySource(PROPERTY_SOURCE, mapOf(LOCAL_PORT_KEY to bound.port)))
        log.info {
            "The control plane's service port listens on ${bound.port}: the service profile only (CTL-58), with the application's server settings (CTL-71)."
        }
    }

    override fun stop() {
        val bound = server ?: return
        server = null
        // Unbinds first, so no new connection arrives; the requests in flight get the grace; stopping the server then closes every
        // connection still open, the idle keep-alive ones included (a test holds it, on a real application, for each setting of
        // `server.shutdown`).
        bound.shutDownGracefully { }
        val deadline = System.nanoTime() + grace.toNanos()
        while (inFlight.get() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MILLIS)
        }
        bound.stop()
        boundPort = 0
    }

    override fun isRunning(): Boolean = server != null

    /** After the application is up and before it is announced; stops before the application's own server does. */
    override fun getPhase(): Int = SmartLifecycle.DEFAULT_PHASE - PHASE_BELOW_SERVER

    internal companion object {
        /** The port it bound, as the environment announces it: `local.control.service.port`. */
        const val LOCAL_PORT_KEY = "local.control.service.port"
        private const val PROPERTY_SOURCE = "controlServicePort"
        private const val POLL_MILLIS = 25L
        private const val PHASE_BELOW_SERVER = 512
    }
}
