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
import org.springframework.boot.context.properties.ConfigurationProperties
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

    class Http {
        /** Where the control API's routes are served (RFC 0007 §10.2). */
        var basePath: String = "/cosec/control/v1"
    }

    class Revisions {
        var maxCount: Int = 50
        var minAge: Duration = Duration.ofDays(7)
        var orphanAge: Duration = Duration.ofHours(1)
    }

    class Changes {
        /** An open change request expires this long after it was proposed. */
        var expireAfter: Duration = Duration.ofDays(7)
    }

    class Approval {
        var expireAfter: Duration = Duration.ofHours(72)

        /** Whether every publish, not only an escalated one, needs a second person's approval. */
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
    }

    companion object {
        const val PREFIX = CoSec.COSEC_PREFIX + "control"
    }
}
