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

package me.ahoo.cosec.cocache.shadow

import me.ahoo.cocache.api.Codec
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.shadow.ShadowCodec
import me.ahoo.cosec.spi.shadow.InstanceStatus

/** One instance's status, in the format of [ShadowCodec.encodeStatuses] with the one instance. */
internal object InstanceStatusCodec : Codec<InstanceStatus> {
    override fun encode(value: InstanceStatus): ByteArray = ShadowCodec.encodeStatuses(mapOf(value.instance to value))

    override fun decode(bytes: ByteArray): InstanceStatus {
        val statuses = ShadowCodec.decodeStatuses(bytes)
        require(statuses.size == 1) { "A stored status is one instance's, not ${statuses.size}" }
        return statuses.values.single()
    }
}
