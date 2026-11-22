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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.CompiledDocument
import me.ahoo.cosec.gateway.PipelineGatewayFilter
import me.ahoo.cosec.runtime.snapshot.DocumentReadiness
import me.ahoo.cosec.runtime.snapshot.SnapshotSource
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spring.boot.StartupTask
import org.springframework.beans.factory.ObjectProvider
import org.springframework.cloud.gateway.event.RefreshRoutesResultEvent
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.route.RouteLocator
import org.springframework.context.ApplicationListener
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

/**
 * A gateway holds the documents of the apps its routes name (`SNP-20`): the `cosec-app-id` of every route
 * ([PipelineGatewayFilter.APP_METADATA]), the app its requests are decided in. Once every bean exists, before the
 * gateway reports ready, it reads its routes and adds their apps to what readiness waits for, as `prewarm-apps` are
 * ([DocumentReadiness.prewarmAlso]). When the routes are refreshed, an app a route names for the first time is loaded
 * then, off the request path; a request for it before that load ends takes the ordinary cold load. An app whose routes
 * went away stays held, like any document within its family's bound.
 *
 * A route whose app cannot name a document is left out: its requests are refused (500) on the request path, as they
 * are without this. Routes that cannot be read in [READ_ROUTES] leave the gateway as it was before `SNP-20`: their apps
 * load on their first request.
 */
@InternalCoSecApi
class GatewayRouteApps(
    private val routesOf: ObjectProvider<RouteLocator>,
    private val readinessOf: ObjectProvider<DocumentReadiness>,
    private val sourceOf: ObjectProvider<SnapshotSource<CompiledDocument, CompiledDocument>>,
) : StartupTask.AfterSingletons, ApplicationListener<RefreshRoutesResultEvent> {
    /** The apps already asked for, so that a refresh loads only the new ones. */
    private val known: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun afterSingletonsInstantiated() {
        // A host without a snapshot store holds no documents; one without routes has none to name.
        val routes = routesOf.ifAvailable ?: return
        val readiness = readinessOf.ifAvailable ?: return

        @Suppress("TooGenericExceptionCaught")
        val apps = try {
            // Before the host reports ready, on the thread that starts it: no event loop is waiting on this.
            routes.routes.collectList().block(READ_ROUTES).orEmpty().let(::appsOf)
        } catch (e: RuntimeException) {
            log.warn(e) { "The gateway's routes could not be read at startup; their apps load on their first request." }
            return
        }
        known += apps
        readiness.prewarmAlso(apps.map { SnapshotKey.App(it) })
    }

    override fun onApplicationEvent(event: RefreshRoutesResultEvent) {
        val routes = routesOf.ifAvailable
        val source = sourceOf.ifAvailable
        if (!event.isSuccess || routes == null || source == null) {
            return
        }
        routes.routes.collectList().subscribe(
            { refreshed ->
                val added = appsOf(refreshed).filter(known::add)
                if (added.isNotEmpty()) {
                    source.prewarm(added.map { SnapshotKey.App(it) })
                }
            },
            ::refreshFailed,
        )
    }

    private fun refreshFailed(error: Throwable) {
        log.warn(
            error
        ) { "The gateway's refreshed routes could not be read; their new apps load on their first request." }
    }

    @InternalCoSecApi
    companion object {
        /** How long startup waits for the routes: a gateway whose routes take longer starts without holding their apps. */
        val READ_ROUTES: Duration = Duration.ofSeconds(30)

        /** The apps [routes] name, each once: only a string that can name an app document. */
        fun appsOf(routes: List<Route>): Set<String> = routes.mapNotNullTo(LinkedHashSet()) { route ->
            (route.metadata[PipelineGatewayFilter.APP_METADATA] as? String)?.takeIf(SnapshotKey.App::isValidAppId)
        }
    }
}
