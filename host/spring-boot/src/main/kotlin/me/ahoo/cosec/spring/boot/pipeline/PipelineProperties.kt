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

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.identity.IdentityCodec
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.snapshot.SnapshotOptions
import me.ahoo.cosec.spring.boot.CoSecProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.Environment
import org.springframework.util.unit.DataSize
import java.time.Duration

/** `cosec.pipeline.*` (RFC 0006 X8): the request pipeline's own bounds. */
@ConfigurationProperties(prefix = PipelineProperties.PREFIX)
@InternalCoSecApi
class PipelineProperties {
    /** The whole evaluation of one request; past it the request is answered 503. */
    var timeout: Duration = Duration.ofSeconds(5)

    /** Authenticating the request's credentials. */
    var authenticationTimeout: Duration = Duration.ofSeconds(1)

    /** Whether a token may come in the query string; off, since URLs end up in logs. */
    var allowQueryToken: Boolean = false

    /**
     * Whether a token may come in the `authorization` cookie; off, since a browser sends a cookie on its own, on a
     * cross-site request too. Turn it on only with your own CSRF defense.
     */
    var allowCookieToken: Boolean = false

    /** The header an API key comes in. No document may read it (`CND-5`, [credentialHeaders]). */
    var apiKeyHeader: String = DEFAULT_API_KEY_HEADER

    /** How much of an evaluation's trace decision events carry. */
    var traceLevel: TraceLevel = TraceLevel.OFF

    /** `enforce` decides with policies; `gateway` trusts the identity the gateway in front forwards (`CoSec-Identity`). */
    var mode: Mode = Mode.ENFORCE

    /** `cosec.pipeline.identity.*`: how a service behind a gateway reads the identity forwarded to it. */
    val identity = Identity()

    @InternalCoSecApi
    enum class Mode { ENFORCE, GATEWAY }

    @InternalCoSecApi
    class Identity {
        /** An identity older than this (plus `cosec.authentication.revocation.skew`) is refused: 401 `token-expired`. */
        var maxAge: Duration = Duration.ofSeconds(60)

        /** A larger header is refused: 400 `invalid-request`. */
        var maxSize: DataSize = DataSize.ofBytes(IdentityCodec.DEFAULT_MAX_BYTES.toLong())

        /**
         * The statement that only the gateway can reach this service (`STA-7`). A service in `gateway` mode believes any
         * `CoSec-Identity` it receives, a header with no signature, so whoever else can reach it is whoever they claim to
         * be. No default: the mode does not start until this is `true`, written by an operator who has made it so (a
         * NetworkPolicy that admits the gateway's pods only, mutual TLS, a private network).
         */
        var onlyGatewayCanReach: Boolean = false
    }

    @InternalCoSecApi
    companion object {
        const val PREFIX = CoSecProperties.PREFIX + ".pipeline"
        const val MODE_KEY = "$PREFIX.mode"
        const val ONLY_GATEWAY_CAN_REACH_KEY = "$PREFIX.identity.only-gateway-can-reach"
        const val API_KEY_HEADER_KEY = "$PREFIX.api-key-header"
        const val DEFAULT_API_KEY_HEADER = "X-API-Key"
    }
}

/**
 * The headers this host reads credentials from besides the fixed ones of `CND-5`: its API key header, as
 * `cosec.pipeline.api-key-header` names it, read as [PipelineProperties] binds it. Every compiler of the host, the data
 * plane's and the control plane's, refuses a reference to it, as the pipeline refuses to read it (RC1-21).
 */
internal fun credentialHeaders(environment: Environment): Set<String> = setOf(
    Binder.get(environment).bind(PipelineProperties.API_KEY_HEADER_KEY, String::class.java).orElse(null)
        ?: PipelineProperties.DEFAULT_API_KEY_HEADER,
)

/** `cosec.gateway.*`: what a Spring Cloud Gateway host lets through besides its routes. */
@ConfigurationProperties(prefix = GatewayProperties.PREFIX)
@InternalCoSecApi
class GatewayProperties {
    /**
     * Actuator endpoints that may be exposed over HTTP on the gateway's application port, where CoSec does not authorize
     * them, because something else protects them. Any other one but `health` fails startup (`STA-2`, RC1-48); `gateway`,
     * which writes routes, is never accepted here.
     */
    var unprotectedEndpoints: Set<String> = emptySet()

    @InternalCoSecApi
    companion object {
        const val PREFIX = CoSecProperties.PREFIX + ".gateway"
        const val UNPROTECTED_ENDPOINTS_KEY = "$PREFIX.unprotected-endpoints"
    }
}

/** `cosec.gateway.identity.*`: what a gateway forwards to the services behind it (`CoSec-Identity`). */
@ConfigurationProperties(prefix = GatewayIdentityProperties.PREFIX)
@InternalCoSecApi
class GatewayIdentityProperties {
    /**
     * Whether a gateway forwards the verified identity: to a `lb://` route, or a route with metadata
     * `cosec-identity: true`; never to one with `cosec-identity: false` (RC1-49).
     */
    var enabled: Boolean = true

    /** The token claims forwarded besides the identity, such as `name` and `email`: strings, numbers, booleans. */
    var claims: Set<String> = emptySet()

    /** A larger identity fails the request closed (500) rather than being cut. */
    var maxSize: DataSize = DataSize.ofBytes(IdentityCodec.DEFAULT_MAX_BYTES.toLong())

    /**
     * Whether the client's credentials still go downstream, to the routes that receive the identity: the
     * `Authorization` header, the API key header (`cosec.pipeline.api-key-header`) and the `authorization` query
     * parameter (RC1-5). Off: a service behind needs none. Turn it on only for services that verify the token again or
     * call other APIs on the user's behalf.
     */
    var forwardAuthorization: Boolean = false

    @InternalCoSecApi
    companion object {
        const val PREFIX = CoSecProperties.PREFIX + ".gateway.identity"
    }
}

/**
 * The 8.0 path's `cosec.authorization.*` properties (RFC 0004 §14.3, RFC 0005 §5.3, RFC 0006 X8, RFC 0003 §4.1): the
 * host's app, the per-stage timeouts, the route map and the policy snapshot.
 */
@ConfigurationProperties(prefix = RuntimeAuthorizationProperties.PREFIX)
@InternalCoSecApi
class RuntimeAuthorizationProperties {
    val scope = Scope()
    val remoteIp = RemoteIp()
    val membership = Membership()
    val attributes = Timed(Duration.ofMillis(300))
    val rateLimit = Timed(Duration.ofMillis(500))
    val routes = Routes()
    val snapshot = Snapshot()
    val shadow = Shadow()

    @InternalCoSecApi
    class Scope {
        /** The app this host serves (RFC 0004 §4.6); unset when requests declare their app. */
        var appId: String? = null
    }

    @InternalCoSecApi
    class RemoteIp {
        /**
         * How many proxy hops of `X-Forwarded-For` to trust: `0` ignores the header (a directly exposed host), `N` takes
         * the entry the closest of N trusted proxies appended.
         */
        var maxTrustedIndex: Int = 1
    }

    @InternalCoSecApi
    class Timed(
        /**
         * How long one call of the stage's collaborator may take, the stage being the group this property is under:
         * `attributes` (an `AttributeProvider` that sets no timeout of its own; past it its attributes are unavailable)
         * and `rate-limit` (the `RateLimiter`; past it the rule's `strictFailure` decides).
         */
        var timeout: Duration,
    )

    @InternalCoSecApi
    class Membership {
        /**
         * How long one call of the `MembershipProvider` may take; past it, or when it fails, the request is 503. The
         * fill of the membership cache takes no longer than this.
         */
        var timeout: Duration = Duration.ofMillis(500)

        /**
         * How long an instance remembers that the loader did not know a principal (a removed member's token still
         * valid), so that its requests do not reach the loader each time. Zero remembers nothing; at most one minute,
         * the default life of a cached membership. A principal added meanwhile is known when it ends, or at once on the
         * instance that invalidated it. A failed load is never remembered.
         */
        var unknownTtl: Duration = Duration.ofSeconds(5)
    }

    @InternalCoSecApi
    class Routes {
        /**
         * The route maps WebFlux and WebMvc hosts load (RFC 0005 §5.3), comma-separated resource locations; a location
         * that does not exist, or a map with no routes, fails startup. Unset, the host runs without a route map, every
         * request in the `http` namespace, unless [DEFAULT_LOCATION] exists: then startup fails, so that a map someone
         * shipped is never left out without a word. Empty runs without a route map on purpose.
         */
        var location: String? = null

        @InternalCoSecApi
        companion object {
            const val DEFAULT_LOCATION = "classpath:cosec-routes.json"
        }
    }

    @InternalCoSecApi
    class Snapshot {
        /**
         * How long an instance serves its local copy of a document before it asks Redis again; the one value for the
         * snapshot cache's local tier, whatever a cache customizer or `cocache.*` property sets.
         */
        var localMaxAge: Duration = Duration.ofSeconds(30)

        /**
         * How often a pass starts that confirms every installed document against Redis; while a pass is still in
         * flight, the next starts as soon as it ends. A change whose invalidation was lost is installed by the first
         * pass that reaches its document (`SNP-6`). At least 1 second, at most 5 minutes, less than `lag-warn` and,
         * with fail-closed tenants, less than `fail-closed.max-lag`; anything else fails startup.
         */
        var revalidateInterval: Duration = Duration.ofSeconds(10)

        /** How long a document may go unconfirmed before it is logged at WARN; not more than `lag-escalate`. */
        var lagWarn: Duration = Duration.ofSeconds(60)

        /** How long a document may go unconfirmed before it is logged at ERROR as stale. */
        var lagEscalate: Duration = Duration.ofMinutes(5)
        val failClosed = FailClosed()

        /** How long a request waits for a document that is not installed; past it the document counts as failed. Positive. */
        var coldLoadTimeout: Duration = Duration.ofSeconds(2)

        /** The most app documents held in memory (positive); the least recently used one is dropped beyond it. */
        var maxApps: Int = SnapshotOptions.DEFAULT_MAX_APPS

        /**
         * The most tenant documents held in memory (positive); the platform document is never dropped. About 30 KB each
         * for a tenant document of 5 roles of 20 permissions and 20 bindings: size the heap for it.
         */
        var maxTenants: Int = SnapshotOptions.DEFAULT_MAX_TENANTS

        /**
         * The most app ids held as having no app document (positive, `SNP-7`). App ids come from requests, so this
         * bounds what a client naming made-up apps makes an instance remember.
         */
        var maxAbsentApps: Int = SnapshotOptions.DEFAULT_MAX_ABSENT_APPS

        /**
         * The most tenant ids held as having no tenant document (positive, `SNP-7`), about 470 bytes each. Each request of
         * such a tenant reads its entry: one dropped is read from Redis again on the tenant's next request.
         */
        var maxAbsentTenants: Int = SnapshotOptions.DEFAULT_MAX_ABSENT_TENANTS

        /**
         * Documents a revalidation pass confirms at once (positive). A pass takes about the documents held × one
         * conditional read ÷ this; past `revalidate-interval`, passes run back to back and lag grows with them.
         */
        var reloadConcurrency: Int = SnapshotOptions.DEFAULT_RELOAD_CONCURRENCY

        /** App documents loaded at startup, after the platform document; readiness waits for them. */
        var prewarmApps: List<String> = emptyList()

        /** Tenant documents loaded at startup, after the platform document; readiness waits for them. */
        var prewarmTenants: List<String> = emptyList()

        /**
         * Whether the apps this host names are loaded at startup, as `prewarm-apps` are, and held (`SNP-20`): its
         * `cosec.authorization.scope.app-id`, and on a gateway every `cosec-app-id` its routes name, then every app a
         * refreshed route names. Off, they load on their first request, as before.
         */
        var prewarmHostApps: Boolean = true

        /** Threads that compile documents; positive. */
        var compileThreads: Int = 2

        /** What a Redis whose `maxmemory-policy` is not `noeviction`, or cannot be read, does to startup (`SNP-3`). */
        var evictionCheck: EvictionCheck = EvictionCheck.FAIL

        @InternalCoSecApi
        class FailClosed {
            /**
             * Tenants that must not be decided on old documents: a request of one is 503 unless a DENY matches, when the
             * oldest document it reads was confirmed longer ago than `max-lag`. `*` names every tenant.
             */
            var tenants: Set<String> = emptySet()

            /** The lag beyond which the tenants above fail closed; not negative. */
            var maxLag: Duration = Duration.ofMinutes(30)
        }
    }

    /**
     * The data plane's part of the control plane's shadow windows (RFC 0007 §6.4), with `cosec-cocache`: sampled
     * requests are evaluated again on the staged candidates, and this instance reports what it saw.
     */
    @InternalCoSecApi
    class Shadow {
        /**
         * Off, this instance takes no part in shadow windows: it reports no samples and no `CANDIDATE_ERROR`, so its
         * traffic counts for nothing in a window.
         */
        var enabled: Boolean = true

        /** The share of requests evaluated again on the candidates, from 0 to 1. */
        var sampleRate: Double = 0.1

        /** How often this instance writes its reports and its status. */
        var reportInterval: Duration = Duration.ofSeconds(10)

        /**
         * The prefix of this instance's id in reports and statuses: the id is `<instance-id>.<suffix>`, the suffix new at
         * every start, so that a restarted process never replaces its predecessor's reports; a random UUID when empty.
         * Only letters, digits, `.`, `_` and `-` (it ends a Redis key); any other id fails startup.
         */
        var instanceId: String = ""
    }

    @InternalCoSecApi
    enum class EvictionCheck {
        /** Refuse to start. */
        FAIL,

        /**
         * Start, and log a warning of what eviction would do: for a Redis that denies `INFO` while the operator has
         * set `noeviction` themselves.
         */
        WARN,
    }

    @InternalCoSecApi
    companion object {
        const val PREFIX = CoSecProperties.PREFIX + ".authorization"
    }
}

/** `cosec.observability.listener.*` (RFC 0006 X8): the bound on REQUIRED decision listeners, the queues of BEST_EFFORT ones. */
@ConfigurationProperties(prefix = ListenerProperties.PREFIX)
@InternalCoSecApi
class ListenerProperties {
    /** How long one REQUIRED listener may take; past it a permit becomes 503. */
    var requiredTimeout: Duration = Duration.ofSeconds(1)

    /** The events one BEST_EFFORT listener may have waiting; a full queue drops the next and counts it. */
    var queueCapacity: Int = DecisionListeners.DEFAULT_QUEUE_CAPACITY

    /** On shutdown, how long queued events still go out before the rest is dropped. */
    var drainTimeout: Duration = Duration.ofSeconds(5)

    @InternalCoSecApi
    companion object {
        const val PREFIX = CoSecProperties.PREFIX + ".observability.listener"
    }
}
