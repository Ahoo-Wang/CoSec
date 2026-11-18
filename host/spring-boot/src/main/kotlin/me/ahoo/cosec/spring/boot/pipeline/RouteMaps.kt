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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.spring.boot.pipeline

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.route.RouteMap
import me.ahoo.cosec.document.route.RouteMapCodec
import org.springframework.core.io.ResourceLoader

/**
 * Loads the route maps of a comma-separated list of resource locations, all or nothing, and says what it loaded: which
 * action a request has decides which statements apply to it, so a host's log must show where its routes came from.
 */
internal object RouteMaps {
    private val log = KotlinLogging.logger {}

    private const val LOCATION_KEY = "${RuntimeAuthorizationProperties.PREFIX}.routes.location"
    private const val DEFAULT_LOCATION = RuntimeAuthorizationProperties.Routes.DEFAULT_LOCATION

    /** [locations] null is the property unset; empty is the host running without a route map on purpose. */
    fun load(locations: String?, resourceLoader: ResourceLoader): RouteMap {
        val defaultExists = resourceLoader.getResource(DEFAULT_LOCATION).exists()
        check(locations != null || !defaultExists) {
            "$DEFAULT_LOCATION exists but $LOCATION_KEY is not set, so it is not loaded: set it to $DEFAULT_LOCATION, " +
                "or to empty to run without a route map."
        }
        val sources = locations.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { location ->
            val resource = resourceLoader.getResource(location)
            check(resource.exists()) {
                "The route map [$location] ($LOCATION_KEY) does not exist; set the property empty to run without one."
            }
            location to resource.inputStream.use { it.readBytes() }
        }
        if (sources.isEmpty()) {
            if (defaultExists) {
                log.warn { "$LOCATION_KEY is empty, so $DEFAULT_LOCATION, which exists, is not loaded." }
            }
            log.info { "No route map: requests are authorized as http:<METHOD> on http:path:<path>." }
            return RouteMap.EMPTY
        }
        val documents = sources.map { (location, bytes) -> location to RouteMapCodec.decode(bytes, location) }
        val routes = RouteMap.of(RouteMapCodec.located(documents))
        documents.forEach { (location, document) ->
            log.info { "Route map [$location]: service [${document.service}], ${document.routes.size} routes." }
        }
        return routes
    }
}
