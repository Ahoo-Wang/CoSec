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

package me.ahoo.cosec.spring.boot.control

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.InternalCoSecApi

/**
 * The startup note for `cosec.control.shadow.min-samples=0`. A shadow window closes once `min-duration` has passed and
 * the instances have reported `min-samples` samples that compared; with none required it closes on time alone, with no
 * sample at all if no request came, and the candidate's publication then rests on nothing the window saw. Accepted (it is
 * the operator's configuration, and a deployment with no traffic of its own has no samples to wait for), and said once.
 */
internal object ShadowSampleNote {
    private val log = KotlinLogging.logger {}

    /** Logs [of] at WARN when there is one. */
    fun warn(shadow: ControlProperties.Shadow) {
        of(shadow)?.let { note -> log.warn { note } }
    }

    /** The line to log for [shadow], or null when the window requires samples. */
    fun of(shadow: ControlProperties.Shadow): String? = if (shadow.minSamples != 0L) {
        null
    } else {
        "${ControlProperties.PREFIX}.shadow.min-samples is 0: a shadow window closes once min-duration " +
            "(${shadow.minDuration}) has passed, whatever the instances compared, with no sample at all if no request " +
            "came. A candidate then publishes on time alone, and the shadow step proves nothing. Set min-samples to " +
            "the number of compared requests you want to see first."
    }
}
