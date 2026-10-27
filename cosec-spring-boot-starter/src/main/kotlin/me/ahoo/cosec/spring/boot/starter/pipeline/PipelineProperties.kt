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
import org.springframework.boot.context.properties.ConfigurationProperties
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

    companion object {
        const val PREFIX = CoSec.COSEC_PREFIX + "pipeline"
    }
}

/**
 * The 8.0 path's `cosec.authorization.*` properties (RFC 0004 §14.3, RFC 0005 §5.3, RFC 0006 X8, RFC 0003 §4.1): the
 * host's app, the per-stage timeouts, the route map and the policy snapshot.
 */
@ConfigurationProperties(prefix = RuntimeAuthorizationProperties.PREFIX)
class RuntimeAuthorizationProperties {
    val scope = Scope()
    val membership = Timed(Duration.ofMillis(500))
    val attributes = Timed(Duration.ofMillis(300))
    val rateLimit = Timed(Duration.ofMillis(500))
    val routes = Routes()
    val snapshot = Snapshot()

    class Scope {
        /** The app this host serves (RFC 0004 §4.6); unset when requests declare their app. */
        var appId: String? = null
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

        /** What a Redis whose `maxmemory-policy` is not `noeviction` does to startup (`SNP-3`). */
        var evictionCheck: EvictionCheck = EvictionCheck.FAIL

        class FailClosed {
            var tenants: Set<String> = emptySet()
            var maxLag: Duration = Duration.ofMinutes(30)
        }
    }

    enum class EvictionCheck {
        /** Refuse to start. */
        FAIL,

        /**
         * Start, and log an error: for a managed Redis that hides `CONFIG GET` while the operator has set
         * `noeviction` themselves.
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
