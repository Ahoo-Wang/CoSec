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

package me.ahoo.cosec.spring.boot.starter.pipeline

import me.ahoo.cosec.api.CoSec
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.identity.IdentityCodec
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.time.Duration

/** `cosec.pipeline.*` (RFC 0006 X8): the request pipeline's own bounds. */
@ConfigurationProperties(prefix = PipelineProperties.PREFIX)
class PipelineProperties {
    /** The whole evaluation of one request; past it the request is answered 503. */
    var timeout: Duration = Duration.ofSeconds(5)

    /** Authenticating the request's credentials. */
    var authenticationTimeout: Duration = Duration.ofSeconds(1)

    /** Whether a token may come in the query string; off, since URLs end up in logs. */
    var allowQueryToken: Boolean = false

    /** The header an API key comes in. */
    var apiKeyHeader: String = "X-API-Key"

    /** How much of an evaluation's trace decision events carry. */
    var traceLevel: TraceLevel = TraceLevel.OFF

    /** `enforce` decides with policies; `gateway` trusts the identity the gateway in front forwards (`CoSec-Identity`). */
    var mode: Mode = Mode.ENFORCE

    /** `cosec.pipeline.gateway.*`: how a service behind a gateway reads the forwarded identity. */
    val gateway = Gateway()

    enum class Mode { ENFORCE, GATEWAY }

    class Gateway {
        /** An identity older than this (plus `cosec.authentication.revocation.skew`) is refused: 401 `token-expired`. */
        var maxAge: Duration = Duration.ofSeconds(60)

        /** A larger header is refused: 400 `invalid-request`. */
        var maxSize: DataSize = DataSize.ofBytes(IdentityCodec.DEFAULT_MAX_BYTES.toLong())
    }

    companion object {
        const val PREFIX = CoSec.COSEC_PREFIX + "pipeline"
        const val MODE_KEY = "$PREFIX.mode"
    }
}

/** `cosec.gateway.identity.*`: what a gateway forwards to the services behind it (`CoSec-Identity`). */
@ConfigurationProperties(prefix = GatewayIdentityProperties.PREFIX)
class GatewayIdentityProperties {
    /** Whether a gateway forwards the verified identity; a route opts out with metadata `cosec-identity: false`. */
    var enabled: Boolean = true

    /** The token claims forwarded besides the identity, such as `name` and `email`: strings, numbers, booleans. */
    var claims: Set<String> = emptySet()

    /** A larger identity fails the request closed (500) rather than being cut. */
    var maxSize: DataSize = DataSize.ofBytes(IdentityCodec.DEFAULT_MAX_BYTES.toLong())

    /**
     * Whether the client's `Authorization` header still goes downstream with the identity. Off: a service behind needs
     * no token. Turn it on only for services that call other APIs on the user's behalf.
     */
    var forwardAuthorization: Boolean = false

    companion object {
        const val PREFIX = CoSec.COSEC_PREFIX + "gateway.identity"
    }
}

/**
 * The 8.0 path's `cosec.authorization.*` properties (RFC 0004 §14.3, RFC 0005 §5.3, RFC 0006 X8, RFC 0003 §4.1): the
 * host's app, the per-stage timeouts, the route map and the policy snapshot.
 */
@ConfigurationProperties(prefix = RuntimeAuthorizationProperties.PREFIX)
class RuntimeAuthorizationProperties {
    val scope = Scope()
    val remoteIp = RemoteIp()
    val membership = Timed(Duration.ofMillis(500))
    val attributes = Timed(Duration.ofMillis(300))
    val rateLimit = Timed(Duration.ofMillis(500))
    val routes = Routes()
    val snapshot = Snapshot()
    val shadow = Shadow()

    class Scope {
        /** The app this host serves (RFC 0004 §4.6); unset when requests declare their app. */
        var appId: String? = null
    }

    class RemoteIp {
        /**
         * How many proxy hops of `X-Forwarded-For` to trust: `0` ignores the header (a directly exposed host), `N` takes
         * the entry the closest of N trusted proxies appended.
         */
        var maxTrustedIndex: Int = 1
    }

    class Timed(var timeout: Duration)

    class Routes {
        /**
         * The route maps WebFlux and WebMvc hosts load (RFC 0005 §5.3), comma-separated resource locations. A location
         * that does not exist fails startup: without a map every request falls into the `http` namespace. Set it empty
         * to run without a route map on purpose.
         */
        var location: String = DEFAULT_LOCATION

        companion object {
            const val DEFAULT_LOCATION = "classpath:cosec-routes.json"
        }
    }

    class Snapshot {
        /** The Redis namespace of the snapshot documents, shared by every service that reads the same platform. */
        var keyPrefix: String = CoSec.COSEC

        /**
         * How long an instance serves its local copy of a document before it asks Redis again; the one value for the
         * snapshot cache's local tier, whatever a cache customizer or `cocache.*` property sets.
         */
        var localMaxAge: Duration = Duration.ofSeconds(30)
        var revalidateInterval: Duration = Duration.ofSeconds(10)
        var lagWarn: Duration = Duration.ofSeconds(60)
        var lagEscalate: Duration = Duration.ofMinutes(5)
        val failClosed = FailClosed()
        var coldLoadTimeout: Duration = Duration.ofSeconds(2)
        var maxApps: Int = 1024
        var maxTenants: Int = 1024
        var prewarmApps: List<String> = emptyList()
        var prewarmTenants: List<String> = emptyList()
        var compileThreads: Int = 2

        /** What a Redis whose `maxmemory-policy` is not `noeviction`, or cannot be read, does to startup (`SNP-3`). */
        var evictionCheck: EvictionCheck = EvictionCheck.FAIL

        class FailClosed {
            var tenants: Set<String> = emptySet()
            var maxLag: Duration = Duration.ofMinutes(30)
        }
    }

    /**
     * The data plane's part of the control plane's shadow windows (RFC 0007 §6.4), with `cosec-cocache`: sampled
     * requests are evaluated again on the staged candidates, and this instance reports what it saw.
     */
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
         * This instance's id in reports and statuses; a random one when empty. Unique per running instance, and only
         * letters, digits, `.`, `_` and `-` (it ends a Redis key); any other id fails startup.
         */
        var instanceId: String = ""
    }

    enum class EvictionCheck {
        /** Refuse to start. */
        FAIL,

        /**
         * Start, and log a warning of what eviction would do: for a managed Redis (such as ElastiCache) that hides
         * `CONFIG GET` while the operator has set `noeviction` themselves.
         */
        WARN,
    }

    companion object {
        const val PREFIX = CoSec.COSEC_PREFIX + "authorization"
    }
}

/** `cosec.authentication.revocation.*` (RFC 0004 §4.5.3). */
@ConfigurationProperties(prefix = RevocationProperties.PREFIX)
class RevocationProperties {
    /** How much earlier than a subject revocation a token may have been issued and still be revoked by it. */
    var skew: Duration = Duration.ofSeconds(5)

    companion object {
        const val PREFIX = CoSec.COSEC_PREFIX + "authentication.revocation"
    }
}

/** `cosec.audit.required.*` (RFC 0006 X8): how long a REQUIRED decision listener may take. */
@ConfigurationProperties(prefix = RequiredListenerProperties.PREFIX)
class RequiredListenerProperties {
    var timeout: Duration = Duration.ofSeconds(1)

    companion object {
        const val PREFIX = CoSec.COSEC_PREFIX + "audit.required"
    }
}

/** `cosec.observability.listener.*` (RFC 0006 X8): the queues of the BEST_EFFORT decision listeners. */
@ConfigurationProperties(prefix = ListenerProperties.PREFIX)
class ListenerProperties {
    /** The events one BEST_EFFORT listener may have waiting; a full queue drops the next and counts it. */
    var queueCapacity: Int = DecisionListeners.DEFAULT_QUEUE_CAPACITY

    /** On shutdown, how long queued events still go out before the rest is dropped. */
    var drainTimeout: Duration = Duration.ofSeconds(5)

    companion object {
        const val PREFIX = CoSec.COSEC_PREFIX + "observability.listener"
    }
}
