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

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.flat.CoSecFlatEngine
import me.ahoo.cosec.engine.flat.FlatEngine
import me.ahoo.cosec.runtime.attribute.AttributeProviders
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.identity.IdentityIssuer
import me.ahoo.cosec.runtime.route.RouteMap
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.id.IdGenerator
import me.ahoo.cosec.spi.id.RandomIdGenerator
import me.ahoo.cosec.spi.membership.MembershipProvider
import me.ahoo.cosec.spi.ratelimit.RateLimiter
import java.time.Clock

/**
 * Everything a [Pipeline] runs with. Only [authenticator] and [snapshots] are required; every other component has a
 * default. A host without Spring Boot assembles one with [SuspendingSnapshotSource.of] and the constructor below; a new
 * component is a new field with a default here, added with another secondary constructor, not a new [Pipeline] parameter.
 */
class PipelineComponents private constructor(
    val authenticator: Authenticator,
    val snapshots: SuspendingSnapshotSource,
    /** Asked when a token carries the provider marker instead of claims; without one such a request is a 503. */
    val membershipProvider: MembershipProvider?,
    internal val engine: FlatEngine,
    /** The ids of requests without a plain `CoSec-Request-Id` header, and of decision events. */
    val idGenerator: IdGenerator,
    val clock: Clock,
    val options: PipelineOptions,
    /** The external attribute providers; the documents must be compiled with their [AttributeProviders.catalog]. */
    val attributes: AttributeProviders,
    /** The decision listeners (RFC 0006 §9); none by default, and then no event is made. */
    val listeners: DecisionListeners,
    /**
     * Counts the permits of rate limit rules (RFC 0006 §8.4). Without one, a permit with obligations finds the limiter
     * unavailable: a strict rule refuses (503), any other is skipped.
     */
    val rateLimiter: RateLimiter?,
    /**
     * The service's routes (RFC 0005 §5.3): a mapped request has its route's `service:verb` on a typed resource, any
     * other request `http:<METHOD>` on its path. None by default: every request is in the `http` namespace.
     */
    val routes: RouteMap,
    /**
     * At a gateway (`cosec.gateway.identity.enabled`): forwards the verified identity to the services behind it in
     * `CoSec-Identity`, and strips the client's. Null elsewhere.
     */
    val identity: IdentityIssuer?,
) {
    constructor(
        authenticator: Authenticator,
        snapshots: SuspendingSnapshotSource,
        membershipProvider: MembershipProvider? = null,
        idGenerator: IdGenerator = RandomIdGenerator,
        clock: Clock = Clock.systemUTC(),
        options: PipelineOptions = PipelineOptions(),
        attributes: AttributeProviders = AttributeProviders.NONE,
        listeners: DecisionListeners = DecisionListeners.NONE,
        rateLimiter: RateLimiter? = null,
        routes: RouteMap = RouteMap.EMPTY,
        identity: IdentityIssuer? = null,
    ) : this(authenticator, snapshots, membershipProvider, CoSecFlatEngine, idGenerator, clock, options, attributes, listeners, rateLimiter, routes, identity)

    /** As the public constructor, with another [engine]: for CoSec's own tests. */
    @InternalCoSecApi
    constructor(
        authenticator: Authenticator,
        snapshots: SuspendingSnapshotSource,
        engine: FlatEngine,
        membershipProvider: MembershipProvider? = null,
        idGenerator: IdGenerator = RandomIdGenerator,
        clock: Clock = Clock.systemUTC(),
        options: PipelineOptions = PipelineOptions(),
        attributes: AttributeProviders = AttributeProviders.NONE,
        listeners: DecisionListeners = DecisionListeners.NONE,
        rateLimiter: RateLimiter? = null,
        routes: RouteMap = RouteMap.EMPTY,
        identity: IdentityIssuer? = null,
    ) : this(authenticator, snapshots, membershipProvider, engine, idGenerator, clock, options, attributes, listeners, rateLimiter, routes, identity)

    /** These components with some replaced: for CoSec's own tests. A host assembles what it needs with the constructor. */
    @InternalCoSecApi
    @Suppress("LongParameterList")
    fun copy(
        authenticator: Authenticator = this.authenticator,
        snapshots: SuspendingSnapshotSource = this.snapshots,
        membershipProvider: MembershipProvider? = this.membershipProvider,
        engine: FlatEngine = this.engine,
        idGenerator: IdGenerator = this.idGenerator,
        clock: Clock = this.clock,
        options: PipelineOptions = this.options,
        attributes: AttributeProviders = this.attributes,
        listeners: DecisionListeners = this.listeners,
        rateLimiter: RateLimiter? = this.rateLimiter,
        routes: RouteMap = this.routes,
        identity: IdentityIssuer? = this.identity,
    ): PipelineComponents = PipelineComponents(
        authenticator,
        snapshots,
        membershipProvider,
        engine,
        idGenerator,
        clock,
        options,
        attributes,
        listeners,
        rateLimiter,
        routes,
        identity,
    )
}
