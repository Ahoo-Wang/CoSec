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

package me.ahoo.cosec.control.change

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.shadow.InstanceStatus
import java.time.Instant

/**
 * The readers of the store (RFC 0014 R5, `SNP-25`, `CTL-89`): every instance with a status written in the last hour
 * ([me.ahoo.cosec.spi.shadow.ShadowReportReader.READERS_TTL]), each with the release it runs, and the instances whose
 * status is listed and does not decode ([unreadable]), whose release is unknown. [asOf] is when they were read; a
 * status written in the minute before it is live ([me.ahoo.cosec.spi.shadow.ShadowReportReader.STATUS_TTL]).
 */
@InternalCoSecApi
data class InstanceReaders(
    val statuses: List<InstanceStatus>,
    val unreadable: Set<String>,
    val asOf: Instant,
) {
    /**
     * How many instances run each release, `unknown` for one whose status does not say (a release before it) or does not
     * decode: what the control plane logs when it changes, and what its meter counts.
     */
    val releases: Map<String, Int>
        get() = (statuses.map { it.release ?: UNKNOWN_RELEASE } + unreadable.map { UNKNOWN_RELEASE })
            .groupingBy { it }.eachCount().toSortedMap()

    @InternalCoSecApi
    companion object {
        /** The release of an instance whose status does not name one. */
        const val UNKNOWN_RELEASE: String = "unknown"
    }
}
