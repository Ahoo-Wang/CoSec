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

package me.ahoo.cosec.control.audit

import me.ahoo.cosec.annotations.CoSecSpi
import me.ahoo.cosec.runtime.event.Delivery

/** The control plane's audit SPI (RFC 0007 §10.5); delivery as in RFC 0006 §9.4. */
@CoSecSpi
interface ChangeListener {
    val name: String

    val delivery: Delivery get() = Delivery.BEST_EFFORT

    suspend fun onChange(event: ChangeEvent)
}
