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

package me.ahoo.cosec.spring.boot.starter.control

import me.ahoo.cosec.api.CoSec
import me.ahoo.cosec.runtime.event.Delivery
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.time.Duration

/** `cosec.control.*` (RFC 0007 X5): the control plane of the starter's `controlSupport` feature. */
@ConfigurationProperties(prefix = ControlProperties.PREFIX)
class ControlProperties {
    /** The app control-plane actions are authorized in (RFC 0007 §7.2). */
    var appId: String = "cosec-control"
    val http = Http()
    val revisions = Revisions()
    val changes = Changes()
    val approval = Approval()
    val breakGlass = BreakGlass()
    val shadow = Shadow()
    val audit = Audit()

    class Http {
        /** Where the control API's routes are served (RFC 0007 §10.2). */
        var basePath: String = "/cosec/control/v1"

        /**
         * The largest request body the WebMvc routes read; a larger one is answered 400 unread. Twice a document's
         * 8 MB cap (RFC 0003 Q14), for the JSON around it. WebFlux hosts are bounded by `spring.codec.max-in-memory-size`.
         */
        var maxBodySize: DataSize = DataSize.ofMegabytes(16)
    }

    class Revisions {
        var maxCount: Int = 50
        var minAge: Duration = Duration.ofDays(7)
        var orphanAge: Duration = Duration.ofHours(1)

        /** How often each instance tries to reclaim; one instance at a time does each document. */
        var reclaimInterval: Duration = Duration.ofHours(1)
    }

    class Changes {
        /** An open change request expires this long after it was proposed. */
        var expireAfter: Duration = Duration.ofDays(7)
    }

    class Approval {
        var expireAfter: Duration = Duration.ofHours(72)

        /**
         * Whether the trust root's own changes are analyzed too (RFC 0007 Q10): what the analysis cannot prove needs a
         * second administrator's approval. A change that escalates needs one either way.
         */
        var fourEyes: Boolean = false
    }

    class BreakGlass {
        /** The longest a break-glass binding may last; at most 24 hours. */
        var maxDuration: Duration = Duration.ofHours(4)
    }

    class Shadow {
        var minDuration: Duration = Duration.ofMinutes(15)
        var minSamples: Long = 1_000
        var maxDuration: Duration = Duration.ofHours(24)

        /** How often shadowing changes are looked at, by one instance at a time; a window closes up to this late. */
        var checkInterval: Duration = Duration.ofMinutes(1)
    }

    class Audit {
        /** How long every REQUIRED change listener together may take to record a gated step before it fails (503). */
        var requiredTimeout: Duration = Duration.ofSeconds(5)
        val kafka = Kafka()

        /** `KafkaChangeListener`, when `kafka-support` and a `KafkaOperations` bean are present. */
        class Kafka {
            var enabled: Boolean = true
            var topic: String = "cosec-control-audit"

            /** `required` makes publishing and break-glass wait for the broker; break-glass needs one REQUIRED listener. */
            var delivery: Delivery = Delivery.BEST_EFFORT
        }
    }

    companion object {
        const val PREFIX = CoSec.COSEC_PREFIX + "control"
        const val KAFKA_AUDIT_PREFIX = "$PREFIX.audit.kafka"
    }
}
