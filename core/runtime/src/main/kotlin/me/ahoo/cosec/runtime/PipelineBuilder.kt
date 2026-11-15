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

package me.ahoo.cosec.runtime

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.route.RouteMap
import me.ahoo.cosec.engine.flat.CoSecFlatEngine
import me.ahoo.cosec.engine.flat.FlatEngine
import me.ahoo.cosec.runtime.attribute.AttributeProviders
import me.ahoo.cosec.runtime.event.DecisionListener
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.identity.IdentityIssuer
import me.ahoo.cosec.runtime.snapshot.DocumentReadiness
import me.ahoo.cosec.runtime.snapshot.DocumentSnapshotViews
import me.ahoo.cosec.runtime.snapshot.DocumentSnapshots
import me.ahoo.cosec.runtime.snapshot.PlatformCompiler
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.spi.attribute.AttributeProvider
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.id.IdGenerator
import me.ahoo.cosec.spi.id.RandomIdGenerator
import me.ahoo.cosec.spi.membership.MembershipProvider
import me.ahoo.cosec.spi.ratelimit.RateLimiter
import me.ahoo.cosec.spi.snapshot.SnapshotDocumentStore
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.time.Clock
import kotlin.time.toKotlinDuration

/**
 * Assembles a [Pipeline] (RFC 0013 §4.3), from [Pipeline.builder]. Every part has a default but the authenticator and
 * the documents; [build] reads what was set once. A pipeline built over a document store owns the snapshot source it
 * creates and the queues of the listeners given here, and [Pipeline.close] closes them.
 */
@ExperimentalCoSecApi
class PipelineBuilder internal constructor(
    private val authenticator: Authenticator,
    private val store: SnapshotDocumentStore?,
    private val snapshots: SuspendingSnapshotSource?,
) {
    private val settings = PipelineSettings()
    private var settingsSet = false
    private var options: PipelineOptions? = null
    private val snapshotSettings = SnapshotSettings()
    private val listenerSettings = ListenerSettings()
    private var listenerSettingsSet = false
    private var membershipProvider: MembershipProvider? = null
    private val providers = ArrayList<AttributeProvider>()
    private var attributeProviders: AttributeProviders? = null
    private val listeners = ArrayList<DecisionListener>()
    private var decisionListeners: DecisionListeners? = null
    private var rateLimiter: RateLimiter? = null
    private var routes: RouteMap = RouteMap.EMPTY
    private var ids: IdGenerator = RandomIdGenerator
    private var clock: Clock = Clock.systemUTC()
    private var forwarding: IdentityForwardingSettings? = null
    private var identity: IdentityIssuer? = null
    private var engine: FlatEngine = CoSecFlatEngine

    /** The pipeline's timeouts and how it reads credentials. */
    fun options(block: PipelineSettings.() -> Unit): PipelineBuilder = apply {
        settings.block()
        settingsSet = true
    }

    /** How the documents of the store are kept and which are loaded at build. Only for a pipeline over a store. */
    fun snapshots(block: SnapshotSettings.() -> Unit): PipelineBuilder = apply {
        check(store != null) { "This pipeline reads a snapshot source its owner keeps: set its options there." }
        snapshotSettings.block()
    }

    /** Asked when a token carries the provider marker instead of claims; without one such a request is a 503. */
    fun memberships(provider: MembershipProvider): PipelineBuilder = apply { membershipProvider = provider }

    /** External attribute providers (`ext.*`); the store's documents compile against what they declare. */
    fun attributes(vararg providers: AttributeProvider): PipelineBuilder = apply { this.providers += providers }

    /** A decision listener (RFC 0006 §9); its [DecisionListener.delivery] says whether it is REQUIRED. */
    fun listener(listener: DecisionListener): PipelineBuilder = apply { listeners += listener }

    /** How the listeners given with [listener] are run. */
    fun listeners(block: ListenerSettings.() -> Unit): PipelineBuilder = apply {
        listenerSettings.block()
        listenerSettingsSet = true
    }

    /** Counts the permits of rate limit rules; without one a strict rule refuses (503) and any other is skipped. */
    fun rateLimiter(limiter: RateLimiter): PipelineBuilder = apply { rateLimiter = limiter }

    /** The service's routes (RFC 0005 §5.3); none by default, and every request is in the `http` namespace. */
    fun routes(routes: RouteMap): PipelineBuilder = apply { this.routes = routes }

    /** The ids of requests without a plain `CoSec-Request-Id` header, and of decision events. */
    fun ids(generator: IdGenerator): PipelineBuilder = apply { ids = generator }

    /** The clock of the pipeline, of its identity issuer and, over a store, of its snapshot source. */
    fun clock(clock: Clock): PipelineBuilder = apply { this.clock = clock }

    /** At a gateway: forwards the verified identity in `CoSec-Identity` and strips the client's (RFC 0009 §5). */
    fun forwardIdentity(block: IdentityForwardingSettings.() -> Unit = {}): PipelineBuilder = apply {
        forwarding = (forwarding ?: IdentityForwardingSettings()).apply(block)
    }

    /** The options as one value, in place of [options]: for the starter, which reads them from its properties. */
    @InternalCoSecApi
    fun options(options: PipelineOptions): PipelineBuilder = apply { this.options = options }

    /** The providers as the host registered them, in place of [attributes]: the host's documents compile with them. */
    @InternalCoSecApi
    fun attributes(providers: AttributeProviders): PipelineBuilder = apply { attributeProviders = providers }

    /** Listeners the host owns and closes, in place of [listener] and [listeners]: their stats are the host's metrics. */
    @InternalCoSecApi
    fun listeners(listeners: DecisionListeners): PipelineBuilder = apply { decisionListeners = listeners }

    /** The identity issuer as the host configured it, in place of [forwardIdentity]. */
    @InternalCoSecApi
    fun identity(issuer: IdentityIssuer): PipelineBuilder = apply { identity = issuer }

    /** Another engine: for CoSec's own tests. */
    @InternalCoSecApi
    fun engine(engine: FlatEngine): PipelineBuilder = apply { this.engine = engine }

    /**
     * The pipeline. Throws [IllegalStateException] when two inputs say the same thing differently, so that neither is
     * dropped: a listener given with [listener] beside the host's [DecisionListeners] would never hear a decision.
     */
    fun build(): Pipeline {
        check(options == null || !settingsSet) { "Set the options either with options { } or as PipelineOptions." }
        check(attributeProviders == null || providers.isEmpty()) {
            "Give the attribute providers either one by one or as AttributeProviders."
        }
        check(decisionListeners == null || (listeners.isEmpty() && !listenerSettingsSet)) {
            "Give the decision listeners either with listener and listeners { } or as DecisionListeners."
        }
        check(
            identity == null || forwarding == null
        ) { "Forward the identity either with forwardIdentity { } or an issuer." }
        val options = options ?: settings.toOptions()
        val attributes = attributeProviders ?: AttributeProviders(providers.toList())
        val owned = ArrayList<AutoCloseable>()
        val listeners = decisionListeners ?: ownedListeners(owned)
        val issuer = identity ?: forwarding?.let { IdentityIssuer(it.claims, it.maxBytes, clock) }
        if (store == null) {
            return assemble(checkNotNull(snapshots), options, attributes, listeners, issuer, owned, null)
        }
        val compiler = PlatformCompiler(attributes.catalog, credentialHeaders = setOf(options.apiKeyHeader))
        val source = DocumentSnapshots.source(store, compiler, snapshotSettings.toOptions(), clock)
        compiler.bind(source)
        owned += source
        val prewarm = snapshotSettings.prewarmApps.map { SnapshotKey.App(it) } +
            snapshotSettings.prewarmTenants.map { SnapshotKey.Tenant(it) }
        val readiness = DocumentReadiness(source, prewarm).also { it.start() }
        val views = SuspendingSnapshotSource(DocumentSnapshotViews(source, platformRequired = true))
        return assemble(views, options, attributes, listeners, issuer, owned, readiness)
    }

    private fun ownedListeners(owned: MutableList<AutoCloseable>): DecisionListeners {
        if (listeners.isEmpty()) {
            return DecisionListeners.NONE
        }
        val created = DecisionListeners(
            listeners.toList(),
            requiredTimeout = listenerSettings.requiredTimeout.toKotlinDuration(),
            queueCapacity = listenerSettings.queueCapacity,
            drainTimeout = listenerSettings.drainTimeout.toKotlinDuration(),
        )
        // Drained before the source closes: the queued events are delivered while their documents are still there.
        owned += AutoCloseable { created.drain() }
        return created
    }

    /** Both forms end here, so no default can differ between them. */
    private fun assemble(
        snapshots: SuspendingSnapshotSource,
        options: PipelineOptions,
        attributes: AttributeProviders,
        listeners: DecisionListeners,
        identity: IdentityIssuer?,
        owned: List<AutoCloseable>,
        readiness: DocumentReadiness?,
    ): Pipeline = Pipeline(
        PipelineComponents(
            authenticator = authenticator,
            snapshots = snapshots,
            membershipProvider = membershipProvider,
            engine = engine,
            idGenerator = ids,
            clock = clock,
            options = options,
            attributes = attributes,
            listeners = listeners,
            rateLimiter = rateLimiter,
            routes = routes,
            identity = identity,
        ),
        owned,
        readiness,
    )
}
