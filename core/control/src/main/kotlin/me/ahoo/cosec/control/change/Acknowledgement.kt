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

import me.ahoo.cosec.annotations.InternalCoSecApi

/**
 * Which live instances acknowledge an open shadow window (design D2 §5, `CTL-10`): of the instances with a status in the
 * last minute, [acknowledged] list the window in their status, and [waitingFor] do not, by id, sorted. The window cannot
 * pass while [waitingFor] is not empty: an instance there is an older version, or one that cannot read the staged list,
 * or one that has not compiled the candidate yet.
 */
@InternalCoSecApi
class Acknowledgement(val acknowledged: Int, val waitingFor: List<String>) {
    override fun equals(other: Any?): Boolean =
        other is Acknowledgement && other.acknowledged == acknowledged && other.waitingFor == waitingFor

    override fun hashCode(): Int = 31 * acknowledged + waitingFor.hashCode()

    override fun toString(): String = "Acknowledgement(acknowledged=$acknowledged, waitingFor=$waitingFor)"

    @InternalCoSecApi
    companion object {
        /** How many of [waitingFor] an answer or a log line names; the rest are counted. */
        const val SHOWN: Int = 20
    }
}
