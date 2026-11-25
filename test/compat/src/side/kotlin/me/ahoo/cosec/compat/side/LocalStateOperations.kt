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

package me.ahoo.cosec.compat.side

import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.LocalMemberships
import me.ahoo.cosec.cocache.membershipInvalidations
import me.ahoo.cosec.document.route.RouteMapCodec
import me.ahoo.cosec.document.route.RouteResolution
import me.ahoo.cosec.model.Memberships
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * What each instance keeps for itself, and what of it crosses to the others (MIX-10, MIX-19). Memberships stay in the
 * instance; only their invalidations cross: `memberships.watch` holds two memberships under `prefix` and listens for
 * invalidations, `memberships.publish` invalidates one for every instance, `memberships.held` names what is still held.
 * A route map is the instance's own file: `routes.resolve` resolves requests (`METHOD path` lines) with a map.
 */
internal object LocalStateOperations {
    private val watching = ConcurrentHashMap<String, LocalMemberships>()

    val ALL: Map<String, (Map<String, String>) -> String> = mapOf(
        "memberships.watch" to { a ->
            val prefix = a.getValue("prefix")
            val local = LocalMemberships(Duration.ofHours(1), MAX_ENTRIES)
            // Kept open with its runtime for the test's life: the listener is this instance's. Subscribing resets what is
            // held, so the memberships are taken after.
            val runtime = CacheRuntime.builder().backend(Redis.backend).build()
            me.ahoo.cocache.core.CoCacheFactory(runtime).membershipInvalidations(prefix).subscribe(local)
            val none = Memberships(Memberships.Source.PROVIDER, emptyMap(), emptyList())
            HELD.forEach { key -> local.get(key) { CompletableFuture.completedFuture(none) }.join() }
            watching[prefix] = local
            "ok"
        },
        "memberships.publish" to { a ->
            Redis.caches { factory -> runBlocking { factory.membershipInvalidations(a.getValue("prefix")).publish(a.getValue("key")) } }
            "ok"
        },
        "memberships.held" to { a ->
            val local = watching.getValue(a.getValue("prefix"))
            HELD.filter { local.peek(it) != null }.joinToString(",")
        },
        "routes.resolve" to { a ->
            val map = RouteMapCodec.load(listOf("cosec-routes.json" to a.getValue("map").encodeToByteArray()))
            a.getValue("requests").lines().filter { it.isNotEmpty() }.joinToString("\n") { request ->
                when (val resolved = map.resolve(request.substringBefore(' '), request.substringAfter(' '))) {
                    is RouteResolution.Mapped ->
                        "mapped ${resolved.action.namespace}:${resolved.action.verb} ${resolved.resource.type}/${resolved.resource.id}"
                    is RouteResolution.Invalid -> "invalid"
                    else -> "unmapped"
                }
            }
        },
    )

    private const val MAX_ENTRIES = 16
    private val HELD = listOf("k1", "k2")
}
