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

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.PolicyDocumentCompiler
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.engine.CompiledDocument
import me.ahoo.cosec.gateway.PipelineGatewayFilter
import me.ahoo.cosec.runtime.snapshot.DocumentReadiness
import me.ahoo.cosec.runtime.snapshot.DocumentSnapshots
import me.ahoo.cosec.runtime.snapshot.SnapshotMetrics
import me.ahoo.cosec.runtime.snapshot.SnapshotOptions
import me.ahoo.cosec.runtime.snapshot.SnapshotSource
import me.ahoo.cosec.spi.snapshot.InMemorySnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotChangeListener
import me.ahoo.cosec.spi.snapshot.SnapshotDigest
import me.ahoo.cosec.spi.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.cosec.spi.snapshot.SnapshotKind
import me.ahoo.cosec.spi.snapshot.SnapshotRead
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.cloud.gateway.event.RefreshRoutesResultEvent
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.route.RouteLocator
import reactor.core.publisher.Flux
import java.time.Clock
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * `SNP-20` on a gateway: the apps its routes name are prewarmed before it reports ready, one without an app document as
 * well (its readiness waits for the store's "no such document"), and an app a refreshed route names is loaded when the
 * routes are refreshed, without a request waiting for it.
 */
class GatewayRouteAppsTest {
    /** The in-memory store, whose app reads wait for [release] while [holding]. */
    private class HeldStore(val inner: InMemorySnapshotDocumentStore = InMemorySnapshotDocumentStore()) :
        SnapshotDocumentStore {
        @Volatile
        var holding = false
        val held = CopyOnWriteArrayList<Pair<CompletableFuture<SnapshotRead>, () -> SnapshotRead>>()

        private fun answer(key: SnapshotKey, read: () -> SnapshotRead): CompletableFuture<SnapshotRead> {
            if (!holding || key.kind != SnapshotKind.APP) return CompletableFuture.completedFuture(read())
            return CompletableFuture<SnapshotRead>().also { held += it to read }
        }

        fun release() = held.forEach { (future, read) -> future.complete(read()) }

        override fun read(key: SnapshotKey) = answer(key) { inner.read(key).join() }

        override fun readIfChanged(key: SnapshotKey, known: SnapshotDigest?) =
            answer(key) { inner.readIfChanged(key, known).join() }

        override fun subscribe(listener: SnapshotChangeListener): AutoCloseable = inner.subscribe(listener)
    }

    private val store = HeldStore()
    private val routes = CopyOnWriteArrayList<Route>()
    private val locator = RouteLocator { Flux.fromIterable(routes.toList()) }

    private fun <T : Any> provider(value: T?): ObjectProvider<T> = object : ObjectProvider<T> {
        override fun getObject(vararg args: Any?): T = checkNotNull(value)
        override fun getObject(): T = checkNotNull(value)
        override fun getIfAvailable(): T? = value
        override fun getIfUnique(): T? = value
    }

    private fun route(id: String, app: Any?): Route = Route.async().id(id).uri("http://$id").predicate { true }
        .apply { if (app != null) metadata(PipelineGatewayFilter.APP_METADATA, app) }
        .build()

    private fun source(): SnapshotSource<CompiledDocument, CompiledDocument> {
        store.inner.put(SnapshotKey.Platform, document())
        store.inner.put(SHOP, document())
        return DocumentSnapshots.source(
            store,
            PolicyDocumentCompiler(),
            SnapshotOptions(),
            Clock.systemUTC(),
            SnapshotMetrics.NOOP
        )
    }

    private fun apps(source: SnapshotSource<CompiledDocument, CompiledDocument>, readiness: DocumentReadiness) =
        GatewayRouteApps(provider(locator), provider(readiness), provider(source))

    @Test
    fun `the apps two routes name are loaded before the gateway is ready, one without a document as well`() {
        routes += route("shop", "shop")
        routes += route("made-up", "made-up")
        routes += route("plain", null)
        routes += route("odd", 42)
        source().use { source ->
            val readiness = DocumentReadiness(source, emptyList()).also { it.start() }
            runBlocking { readiness.await() }.assert().isEmpty()
            store.holding = true
            apps(source, readiness).afterSingletonsInstantiated()
            // Not ready while the reads are out: the gateway refuses traffic until each app has a definite answer.
            readiness.missing().assert().containsExactlyInAnyOrder(SHOP, MADE_UP)
            store.release()
            runBlocking { readiness.await() }.assert().isEmpty()
            source.entries().map { it.key }.assert().contains(SHOP, MADE_UP)
        }
    }

    @Test
    fun `an app a refreshed route names is loaded then, without a request`() {
        routes += route("shop", "shop")
        source().use { source ->
            val readiness = DocumentReadiness(source, emptyList()).also { it.start() }
            val apps = apps(source, readiness)
            apps.afterSingletonsInstantiated()
            runBlocking { readiness.await() }.assert().isEmpty()
            routes += route("later", "later")
            apps.onApplicationEvent(RefreshRoutesResultEvent(this))
            source.settled(listOf(LATER)).get(WAIT_SECONDS, TimeUnit.SECONDS)
            source.entries().map { it.key }.assert().contains(LATER)
            // Readiness is not taken back for it: the gateway is already taking traffic.
            readiness.missing().assert().isEmpty()
        }
    }

    @Test
    fun `a failed refresh, or a host without routes or a store, does nothing`() {
        source().use { source ->
            val readiness = DocumentReadiness(source, emptyList()).also { it.start() }
            GatewayRouteApps(provider(null), provider(readiness), provider(source)).afterSingletonsInstantiated()
            GatewayRouteApps(provider(locator), provider(null), provider(source)).afterSingletonsInstantiated()
            routes += route("shop", "shop")
            apps(source, readiness).onApplicationEvent(RefreshRoutesResultEvent(this, IllegalStateException("failed")))
            GatewayRouteApps(provider(locator), provider(readiness), provider(null))
                .onApplicationEvent(RefreshRoutesResultEvent(this))
            runBlocking { readiness.await() }.assert().isEmpty()
            source.entries().map { it.key }.assert().doesNotContain(SHOP)
        }
    }

    private fun document() = SnapshotDocuments.read(
        """{"schema":2,"revision":1,"publishedAt":"2026-11-20T00:00:00Z"}""".encodeToByteArray()
    )

    private companion object {
        val SHOP = SnapshotKey.App("shop")
        val MADE_UP = SnapshotKey.App("made-up")
        val LATER = SnapshotKey.App("later")
        const val WAIT_SECONDS = 5L
    }
}
