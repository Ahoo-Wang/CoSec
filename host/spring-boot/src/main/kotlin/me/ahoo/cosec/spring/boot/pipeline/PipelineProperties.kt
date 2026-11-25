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
import me.ahoo.cosec.document.identity.IdentityCodec
import me.ahoo.cosec.engine.TraceLevel
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
    /**
     * The whole evaluation of one request; past it the request is answered 503. Every stage's own timeout is a share
     * of it (`PIP-33`): authentication 20 %, memberships 10 %, an attribute provider without its own 6 %, a rate limit
     * 10 %, each of CoSec's revocation reads 30 % of the authentication's, a REQUIRED decision listener 20 %, a
     * REQUIRED change listener all of it. Startup logs what they came to.
     */
    var timeout: Duration = Duration.ofSeconds(5)

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

    /**
     * Where the caller of a request comes from on a host that decides with policies (`enforce`): `credential`, the
     * token or API key the request carries, verified here; or `forwarded-identity`, the `CoSec-Identity` a gateway or a
     * trusted service states, believed under the operator's statement that only trusted callers can reach the host
     * ([Identity.onlyTrustedCallersCanReach], [Identity.onlyGatewayCanReach]). The control plane is wired in `enforce`, and
     * a control host or an IAM service that embeds it takes its callers from the identity.
     */
    var caller: Caller = Caller.CREDENTIAL

    /** `cosec.pipeline.identity.*`: how a host that believes the identity forwarded to it reads it. */
    val identity = Identity()

    @InternalCoSecApi
    enum class Mode { ENFORCE, GATEWAY }

    @InternalCoSecApi
    enum class Caller { CREDENTIAL, FORWARDED_IDENTITY }

    @InternalCoSecApi
    class Identity {
        /** An identity older than this (plus `cosec.authentication.revocation.skew`) is refused: 401 `token-expired`. */
        var maxAge: Duration = Duration.ofSeconds(60)

        /** A larger header is refused: 400 `invalid-request`. */
        // R of IDN-43: 8 KB, which no 8.x version lowers, since every 8.x gateway writes within it. The web server's
        // request-header limit is derived from it when the operator does not set one (IdentityHeaderLimit).
        var maxSize: DataSize = DataSize.ofBytes(IdentityCodec.READ_MAX_BYTES.toLong())

        /**
         * The statement that only the gateway can reach this service (`STA-7`). A service in `gateway` mode believes any
         * `CoSec-Identity` it receives, a header with no signature, so whoever else can reach it is whoever they claim to
         * be. No default: the mode does not start until this is `true`, written by an operator who has made it so (a
         * NetworkPolicy that admits the gateway's pods only, mutual TLS, a private network).
         */
        var onlyGatewayCanReach: Boolean = false

        /**
         * The statement that only the gateway and the services the operator names can reach this host (`STA-9`): the
         * statement of a host that services call directly, such as a control host. It satisfies the same check as
         * [onlyGatewayCanReach], and says what the host's network admits.
         */
        var onlyTrustedCallersCanReach: Boolean = false
    }

    @InternalCoSecApi
    companion object {
        const val PREFIX = CoSecProperties.PREFIX + ".pipeline"
        const val MODE_KEY = "$PREFIX.mode"
        const val CALLER_KEY = "$PREFIX.caller"
        const val ONLY_GATEWAY_CAN_REACH_KEY = "$PREFIX.identity.only-gateway-can-reach"
        const val ONLY_TRUSTED_CALLERS_CAN_REACH_KEY = "$PREFIX.identity.only-trusted-callers-can-reach"
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

    /**
     * The application's own controllers and functional routes, by bean name, that may be served on the gateway host,
     * where CoSec does not decide them (they are mapped before any gateway route), because something else protects them.
     * Any other one fails startup (`STA-13`); a name with `*` is refused, since no handler is listed by a pattern. A
     * statement only the operator can make: which handlers need no CoSec decision is not something a process observes.
     */
    var unprotectedHandlers: Set<String> = emptySet()

    @InternalCoSecApi
    companion object {
        const val PREFIX = CoSecProperties.PREFIX + ".gateway"
        const val UNPROTECTED_ENDPOINTS_KEY = "$PREFIX.unprotected-endpoints"
        const val UNPROTECTED_HANDLERS_KEY = "$PREFIX.unprotected-handlers"
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
    // W of IDN-43: 6 KB, below the 8 KB every 8.x service reads by the room the members a later version adds may take.
    var maxSize: DataSize = DataSize.ofBytes(IdentityCodec.WRITE_MAX_BYTES.toLong())

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
 * host's app, the route map and the policy snapshot. The stages' timeouts are shares of `cosec.pipeline.timeout`.
 */
@ConfigurationProperties(prefix = RuntimeAuthorizationProperties.PREFIX)
@InternalCoSecApi
class RuntimeAuthorizationProperties {
    val scope = Scope()
    val remoteIp = RemoteIp()
    val routes = Routes()
    val snapshot = Snapshot()
    val shadow = Shadow()
    val membership = Membership()

    @InternalCoSecApi
    class Membership {
        /**
         * How long an instance keeps the memberships its `MembershipLoader` answered for a principal: the longest a
         * membership change takes to apply on an instance that a `CoCacheMembershipProvider.invalidate` did not reach
         * (RFC 0001 §3.2). Longer asks the loader less often; shorter applies a missed change sooner. Each instance
         * holds its own copy and asks the loader itself (DR1-D-2).
         */
        var ttl: Duration = Duration.ofMinutes(1)
    }

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
         * How often a pass starts that confirms every installed document against Redis; while a pass is still in
         * flight, the next starts as soon as it ends. A change whose invalidation was lost is installed by the first
         * pass that reaches its document (`SNP-6`). At least 1 second, at most 5 minutes and, with fail-closed tenants,
         * less than `fail-closed.max-lag`; anything else fails startup. A document unconfirmed for 6 intervals is logged
         * at WARN, for 30 at ERROR as stale ([SnapshotOptions.derive]). An instance serves its local copy of a document
         * for 3 intervals before it asks Redis again ([DerivedTimeouts.SNAPSHOT_LOCAL_COPY_INTERVALS]).
         */
        var revalidateInterval: Duration = Duration.ofSeconds(10)

        val failClosed = FailClosed()

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
