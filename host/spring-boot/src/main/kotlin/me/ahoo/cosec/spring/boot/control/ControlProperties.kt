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

package me.ahoo.cosec.spring.boot.control

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.spring.boot.CoSecProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

/** `cosec.control.*` (RFC 0007 X5): the control plane, with `cosec-control`. */
@ConfigurationProperties(prefix = ControlProperties.PREFIX)
@InternalCoSecApi
class ControlProperties {
    /**
     * Whether this instance runs the control plane, when `cosec-control` and `cosec-cocache` are on its classpath.
     * `false` leaves it out, and the instance reads the policy documents and candidates read-only (SNP-18): a fleet
     * that ships one image for both roles sets it on the data-plane role. `true` or `false` only.
     */
    var enabled: Boolean = true

    /** The app control-plane actions are authorized in (RFC 0007 §7.2). */
    var appId: String = "cosec-control"

    /**
     * The control API's base on a control-plane host's service port, such as `http://control:8082/cosec/control/v1`,
     * for an application that calls the control plane rather than embedding it: the starter's `ControlService` is then
     * `HttpControlService` (`cosec-control-http`), which states each caller in `CoSec-Identity`. Only the operator knows
     * where the host is. Startup fails when it is set on an instance that runs the control plane itself (`STA-11`): an
     * application has one `ControlService`.
     */
    var serviceUrl: URI? = null
    val http = Http()
    val revisions = Revisions()
    val changes = Changes()
    val approval = Approval()
    val breakGlass = BreakGlass()
    val shadow = Shadow()
    val audit = Audit()
    val analysis = Analysis()

    @InternalCoSecApi
    class Analysis {
        /**
         * The services' route maps, comma-separated resource locations (UC-1): the control plane lists, for a change,
         * the `http:` DENY statements and rate limit rules a mapped route escapes. Unset, nothing is compared, and the
         * change says so. Each host's own WARN needs only its own route map, not this.
         */
        var routeMaps: String? = null
    }

    @InternalCoSecApi
    class Http {
        /**
         * Whether the control API's routes are served on this instance (`CTL-58`): `false` serves none, for an
         * application that embeds the control plane and reaches it in process only. `true` or `false` only.
         */
        var enabled: Boolean = true

        /**
         * A port of its own for services that call the control plane directly, serving the narrow service profile
         * (`CTL-58`): tenant and app documents, and the change flow that needs no person. Unset, no such port.
         * WebFlux only; it is not the application's port or the management port. Reaching it is a network decision (a
         * policy that admits the labelled service pods), and the host then states `only-trusted-callers-can-reach`.
         */
        var servicePort: Int? = null

        /** Where the control API is served (RFC 0007 §10.2): its routes are under `{base-path}/v1`. */
        var basePath: String = "/cosec/control"
    }

    @InternalCoSecApi
    class Revisions {
        /** The revisions always kept per document; older ones are reclaimed once also older than `min-age`. */
        var maxCount: Int = 50

        /** Revisions younger than this are always kept. */
        var minAge: Duration = Duration.ofDays(7)
    }

    @InternalCoSecApi
    class Changes {
        /** An open change request expires this long after it was proposed. */
        var expireAfter: Duration = Duration.ofDays(7)
    }

    @InternalCoSecApi
    class Approval {
        /**
         * How long an approval stays valid; startup fails when it is longer than `cosec.jwt.token-validity.refresh`,
         * how long a token revocation is kept.
         */
        var expireAfter: Duration = Duration.ofHours(72)

        /**
         * Whether the trust root's own changes are analyzed too (RFC 0007 Q10): what the analysis cannot prove needs a
         * second administrator's approval. A change that escalates needs one either way.
         */
        var fourEyes: Boolean = false
    }

    @InternalCoSecApi
    class BreakGlass {
        /** The longest a break-glass binding may last; at most 24 hours. */
        var maxDuration: Duration = Duration.ofHours(4)
    }

    @InternalCoSecApi
    class Shadow {
        /** A shadow window closes no earlier than this, once it also has `min-samples`. */
        var minDuration: Duration = Duration.ofMinutes(15)

        /** The compared samples a window needs to close before `max-duration`. */
        var minSamples: Long = 1_000

        /** A window closes at this age with fewer samples, as insufficient evidence; not less than `min-duration`. */
        var maxDuration: Duration = Duration.ofHours(24)
    }

    @InternalCoSecApi
    class Audit {
        /** How long every REQUIRED change listener together may take to record a gated step before it fails (503). */
        var requiredTimeout: Duration = Duration.ofSeconds(5)
        val kafka = Kafka()

        /** `KafkaChangeListener`, when `cosec-kafka` and a `KafkaOperations` bean are present. */
        @InternalCoSecApi
        class Kafka {
            /** Whether the Kafka change listener is on; it needs `cosec-kafka` and a `KafkaOperations` bean. */
            var enabled: Boolean = true

            /** The Kafka topic of the change events. */
            var topic: String = "cosec-control-audit"

            /** `required` makes publishing and break-glass wait for the broker; break-glass needs one REQUIRED listener. */
            var delivery: Delivery = Delivery.BEST_EFFORT
        }
    }

    @InternalCoSecApi
    companion object {
        const val PREFIX = CoSecProperties.PREFIX + ".control"
        const val KAFKA_AUDIT_PREFIX = "$PREFIX.audit.kafka"
    }
}
