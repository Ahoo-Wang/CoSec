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

package me.ahoo.cosec.runtime.shadow

import me.ahoo.cosec.annotations.CoSecSpi
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import java.time.Duration

/** What the control plane reads back: every instance's report of a window, and the statuses written recently. */
@CoSecSpi
@ExperimentalCoSecApi
interface ShadowReportReader {
    suspend fun reports(window: String): List<ShadowReport>

    /** Statuses written within [STATUS_TTL] of now. */
    suspend fun statuses(): List<InstanceStatus>

    companion object {
        /** An instance that has not written its status for this long is not counted (RFC 0007 §6.5). */
        val STATUS_TTL: Duration = Duration.ofMinutes(1)
    }
}
