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

package me.ahoo.cosec.cocache.control

import me.ahoo.cocache.core.KeyEncoder
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.snapshot.SnapshotKeyEncoder
import me.ahoo.cosec.model.Identifiers

/** `<docKey>:<policyId>`: the document key ends before the first `:` after its kind, so policy ids may contain `:`. */
internal object ReferenceKeyEncoder : KeyEncoder<ReferenceKey> {
    /** A policy id with an unpaired surrogate names no key: the Redis key would write it as `?` (RC1-52, `IDN-10`). */
    override fun encode(key: ReferenceKey): String {
        require(Identifiers.isWellFormed(key.policy.value)) {
            "Policy [${Identifiers.printable(key.policy.value)}] names no reference key: an unpaired UTF-16 surrogate " +
                "would share it with another policy."
        }
        return "${SnapshotKeyEncoder.encode(key.target)}:${key.policy.value}"
    }
}
