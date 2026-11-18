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

package me.ahoo.cosec.control.change

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import java.time.Instant

/**
 * A change's shadow window (RFC 0007 §6.4): [window] names it in the candidate and in every instance's report; [closed]
 * is what the reports summed to when it closed, and stays as the evidence the publisher acknowledges.
 */
@ExperimentalCoSecApi
data class ShadowState(
    val window: String,
    val startedAt: Instant,
    val closed: ShadowSummary? = null,
    /**
     * Every instance the control plane has seen report on the window, kept with the change where no data-plane
     * credential writes: one that reported once must report until the window closes (round-2 review).
     */
    val reporters: Set<String> = emptySet(),
)
