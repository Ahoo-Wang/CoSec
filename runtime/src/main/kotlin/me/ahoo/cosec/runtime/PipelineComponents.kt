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

package me.ahoo.cosec.runtime

import me.ahoo.cosec.engine.flat.CoSecFlatEngine
import me.ahoo.cosec.engine.flat.FlatEngine
import me.ahoo.cosec.membership.MembershipProvider
import me.ahoo.cosec.route.RouteMap
import me.ahoo.cosec.runtime.attribute.AttributeProviders
import me.ahoo.cosec.runtime.authn.Authenticator
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.identity.IdentityIssuer
import me.ahoo.cosec.runtime.ratelimit.RateLimiter
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosid.StringIdGenerator
import java.time.Clock

/**
 * Everything a [Pipeline] runs with, as one immutable value. Only [authenticator] and [snapshots] are required; every
 * other component has a default, so a new one is a field with a default here, not a new [Pipeline] parameter, and
 * callers set the components they need by name or with `copy`.
 */
data class PipelineComponents(
    val authenticator: Authenticator,
    val snapshots: SuspendingSnapshotSource,
    /** Asked when a token carries the provider marker instead of claims; without one such a request is a 503. */
    val membershipProvider: MembershipProvider? = null,
    val engine: FlatEngine = CoSecFlatEngine,
    /** Request ids for requests without a `CoSec-Request-Id` header; without one, random ids that never block. */
    val idGenerator: StringIdGenerator? = null,
    val clock: Clock = Clock.systemUTC(),
    val options: PipelineOptions = PipelineOptions(),
    /** The external attribute providers; the documents must be compiled with their [AttributeProviders.catalog]. */
    val attributes: AttributeProviders = AttributeProviders.NONE,
    /** The decision listeners (RFC 0006 §9); none by default, and then no event is made. */
    val listeners: DecisionListeners = DecisionListeners.NONE,
    /**
     * Counts the permits of rate limit rules (RFC 0006 §8.4). Without one, a permit with obligations finds the limiter
     * unavailable: a strict rule refuses (503), any other is skipped.
     */
    val rateLimiter: RateLimiter? = null,
    /**
     * The service's routes (RFC 0005 §5.3): a mapped request has its route's `service:verb` on a typed resource, any
     * other request `http:<METHOD>` on its path. None by default: every request is in the `http` namespace.
     */
    val routes: RouteMap = RouteMap.EMPTY,
    /**
     * At a gateway (`cosec.gateway.identity.enabled`): forwards the verified identity to the services behind it in
     * `CoSec-Identity`, and strips the client's. Null elsewhere.
     */
    val identity: IdentityIssuer? = null,
)
