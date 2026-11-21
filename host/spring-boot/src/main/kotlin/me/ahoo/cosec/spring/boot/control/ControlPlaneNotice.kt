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
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.Environment

/**
 * The one line a host that runs the control plane says once it has started: what it serves and where its callers come
 * from, so that an operator reads, without opening the configuration, whether the control API is reachable over HTTP, on
 * which ports, and whether the host verifies a credential or believes the identity it is stated (`STA-9`).
 */
internal object ControlPlaneNotice {
    private const val SERVER_PORT_KEY = "server.port"
    private const val LOCAL_SERVER_PORT_KEY = "local.server.port"
    private const val HTTP_ENABLED_KEY = ControlProperties.PREFIX + ".http.enabled"
    private const val DEFAULT_SERVER_PORT = 8080

    fun line(environment: Environment, properties: ControlProperties): String {
        val binder = Binder.get(environment)
        val routes = if (binder.bind(HTTP_ENABLED_KEY, Boolean::class.javaObjectType).orElse(null) != false) {
            val port: Int = binder.bind(LOCAL_SERVER_PORT_KEY, Int::class.javaObjectType).orElse(null)
                ?: binder.bind(SERVER_PORT_KEY, Int::class.javaObjectType).orElse(null)
                ?: DEFAULT_SERVER_PORT
            val service: Int? = binder.bind(ControlServiceListener.LOCAL_PORT_KEY, Int::class.javaObjectType).orElse(null)
                ?: binder.bind(ControlServicePort.KEY, Int::class.javaObjectType).orElse(null)
            val ports = "port $port" + (service?.let { "; service port $it" } ?: "")
            "HTTP routes on /${properties.http.basePath.trim('/')} ($ports)"
        } else {
            "no HTTP routes ($HTTP_ENABLED_KEY=false)"
        }
        return "Control plane: $routes, caller from ${caller(environment)}"
    }

    private fun caller(environment: Environment): String {
        if (!GatewayModeTrust.wiredByProperty(environment)) {
            return "the credential"
        }
        val statement = listOf(
            PipelineProperties.ONLY_TRUSTED_CALLERS_CAN_REACH_KEY,
            PipelineProperties.ONLY_GATEWAY_CAN_REACH_KEY,
        ).firstOrNull { GatewayModeTrust.isStated(environment, it) }?.substringAfterLast('.') ?: "none"
        return "the forwarded identity (statement: $statement)"
    }
}
