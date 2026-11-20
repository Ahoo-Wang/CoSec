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

package me.ahoo.cosec.spring.boot.pipeline

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner

/**
 * `SNP-20` on every host: the app it names, `cosec.authorization.scope.app-id`, is prewarmed with the configured apps
 * (a WebFlux or WebMvc host that decides itself, and a gateway's routes without `cosec-app-id`); a gateway also gets the
 * apps of its routes ([GatewayRouteApps]). `prewarm-host-apps=false` leaves both as before; `prewarm-apps` keeps working.
 */
class HostAppsPrewarmTest {
    private fun properties(app: String?, hold: Boolean = true, prewarm: List<String> = emptyList()) =
        RuntimeAuthorizationProperties().apply {
            scope.appId = app
            snapshot.prewarmHostApps = hold
            snapshot.prewarmApps = prewarm
        }

    @Test
    fun `the host's app is prewarmed beside the configured ones, each once`() {
        properties("order", prewarm = listOf("shop", "order")).prewarmKeys().assert()
            .containsExactly(SnapshotKey.Platform, SnapshotKey.App("shop"), SnapshotKey.App("order"))
    }

    @Test
    fun `with prewarm-host-apps off, or no app or one that names no document, only the configured apps are`() {
        properties("order", hold = false, prewarm = listOf("shop")).prewarmKeys().assert()
            .containsExactly(SnapshotKey.Platform, SnapshotKey.App("shop"))
        properties(null).prewarmKeys().assert().containsExactly(SnapshotKey.Platform)
        properties("a:b").prewarmKeys().assert().containsExactly(SnapshotKey.Platform)
    }

    private val web = AutoConfigurations.of(
        CoSecSnapshotAutoConfiguration::class.java,
        CoSecPipelineAutoConfiguration::class.java,
        CoSecPipelineWebAutoConfiguration::class.java,
    )

    @Test
    fun `a gateway holds its routes' apps unless prewarm-host-apps is off, and a WebFlux host has no routes to read`() {
        ReactiveWebApplicationContextRunner().withConfiguration(web).run { context ->
            context.getBeansOfType(GatewayRouteApps::class.java).assert().hasSize(1)
        }
        ReactiveWebApplicationContextRunner().withConfiguration(web)
            .withPropertyValues("cosec.authorization.snapshot.prewarm-host-apps=false")
            .run { context -> context.getBeansOfType(GatewayRouteApps::class.java).assert().isEmpty() }
        ReactiveWebApplicationContextRunner()
            .withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER))
            .withConfiguration(web)
            .withPropertyValues(
                "cosec.authorization.routes.location=",
                "cosec.authorization.remote-ip.max-trusted-index=0"
            )
            .run { context -> context.getBeansOfType(GatewayRouteApps::class.java).assert().isEmpty() }
    }
}
