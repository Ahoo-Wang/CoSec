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

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spring.boot.pipeline.GatewayModeTrust
import me.ahoo.cosec.spring.boot.pipeline.PipelineProperties
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.Environment
import java.time.Duration

/**
 * What the service port (`cosec.control.http.service-port`, `CTL-58`) needs of the host, refused at startup naming what to
 * write: a port of its own, a statement that says services call the host directly, a control API to serve, and the
 * WebFlux stack that serves it.
 */
internal object ControlServicePort {
    const val KEY = ControlProperties.PREFIX + ".http.service-port"
    private const val HTTP_ENABLED_KEY = ControlProperties.PREFIX + ".http.enabled"
    private const val SERVER_PORT_KEY = "server.port"
    private const val MANAGEMENT_PORT_KEY = "management.server.port"
    private const val SHUTDOWN_KEY = "spring.lifecycle.timeout-per-shutdown-phase"
    private const val DEFAULT_SERVER_PORT = 8080
    private val DEFAULT_GRACE: Duration = Duration.ofSeconds(30)

    /** How long the service port waits for the requests in flight when it stops: the application's own shutdown phase. */
    fun grace(environment: Environment): Duration =
        Binder.get(environment).bind(SHUTDOWN_KEY, Duration::class.java).orElse(null) ?: DEFAULT_GRACE

    /** The refusals that need only the environment; none when no service port is set. */
    fun check(environment: Environment) {
        val binder = Binder.get(environment)
        val port = binder.bind(KEY, Int::class.javaObjectType).orElse(null) ?: return
        val serverPort = binder.bind(SERVER_PORT_KEY, Int::class.javaObjectType).orElse(null) ?: DEFAULT_SERVER_PORT
        val managementPort = binder.bind(MANAGEMENT_PORT_KEY, Int::class.javaObjectType).orElse(null)
        check(binder.bind(HTTP_ENABLED_KEY, Boolean::class.javaObjectType).orElse(true) != false) {
            "$KEY=$port is set, but $HTTP_ENABLED_KEY=false serves no control route: the service port would answer " +
                "nothing. Unset one of them (CTL-58)."
        }
        check(port == 0 || (port != serverPort && port != managementPort)) {
            "$KEY=$port is the application's own port ($SERVER_PORT_KEY) or the management port ($MANAGEMENT_PORT_KEY): " +
                "the service port must be its own, since the network admits the services to it alone (CTL-58)."
        }
        // The gateway's statement says only the gateway can reach the host: a service port says services can.
        val gateway = GatewayModeTrust.isStated(environment, PipelineProperties.ONLY_GATEWAY_CAN_REACH_KEY)
        val trusted = GatewayModeTrust.isStated(environment, PipelineProperties.ONLY_TRUSTED_CALLERS_CAN_REACH_KEY)
        check(trusted || !gateway) {
            "$KEY=$port admits services that call this host directly, but ${PipelineProperties.ONLY_GATEWAY_CAN_REACH_KEY}" +
                "=true says only the gateway can reach it. State what is true: " +
                "${PipelineProperties.ONLY_TRUSTED_CALLERS_CAN_REACH_KEY}=true (the environment variable " +
                "COSEC_PIPELINE_IDENTITY_ONLYTRUSTEDCALLERSCANREACH=true) once only the gateway and the services you name " +
                "can reach it, and unset ${PipelineProperties.ONLY_GATEWAY_CAN_REACH_KEY} (STA-9, CTL-58)."
        }
    }

    /** The refusal that needs the beans: a service port with no stack to serve it (Spring MVC). */
    fun requireListener(environment: Environment, beans: ListableBeanFactory) {
        if (Binder.get(environment).bind(KEY, Int::class.javaObjectType).isBound &&
            beans.getBeanNamesForType(ControlServiceListener::class.java).isEmpty()
        ) {
            error(
                "$KEY is set, but this host has no WebFlux server for it: the service port is offered on WebFlux only " +
                    "(cosec-webflux). Unset it, or serve the control API from a WebFlux host (CTL-58)."
            )
        }
    }
}
