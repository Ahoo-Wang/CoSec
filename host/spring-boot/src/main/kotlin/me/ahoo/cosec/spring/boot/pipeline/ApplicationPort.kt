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

package me.ahoo.cosec.spring.boot.pipeline

import org.springframework.boot.web.server.context.WebServerInitializedEvent
import org.springframework.context.ApplicationListener
import org.springframework.web.reactive.function.server.RequestPredicate
import org.springframework.web.reactive.function.server.ServerRequest

/**
 * The port the application's own web server bound (`CTL-69`), which is what its routes are for: CoSec's own routes (the
 * control API, the login routes) answer on it, and on the control API's service port, and on no other port of the host.
 *
 * Spring Boot's WebFlux management server is a child context that inherits the application's `RouterFunction`s and
 * `WebFilter`s, so a route registered for the application is served on the management port too, behind whatever filter
 * the application has. For an application's route that is Spring Boot's behaviour; for CoSec's it is not what the route is
 * for: the management port is reached by monitoring and by kubelets, and on a host that believes the identity it is
 * stated (`STA-9`) it would be the control API for whoever can reach it. The port is read from the server, when it has
 * bound (it may have been `0` or changed), not from a property.
 */
internal class ApplicationPort : ApplicationListener<WebServerInitializedEvent> {
    @Volatile
    private var bound: Int = UNBOUND

    override fun onApplicationEvent(event: WebServerInitializedEvent) {
        // The management child context announces its own server too, to the parent: its namespace is "management".
        if (event.applicationContext.serverNamespace == null) {
            bound = event.webServer.port
        }
    }

    /**
     * Whether a request that arrived on [port] reached the application's server. A request on no connection (a mock
     * exchange has no local address) is the application's. Before the server has announced its port nothing is.
     */
    fun serves(port: Int?): Boolean = port == null || port == bound

    /** A predicate of the routes that answer only on the application's port. */
    fun predicate(): RequestPredicate =
        RequestPredicate { request: ServerRequest -> serves(request.exchange().request.localAddress?.port) }

    private companion object {
        const val UNBOUND = -1
    }
}
