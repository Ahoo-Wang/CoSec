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

/**
 * `<docKey>:<digest>`, the digest as `SHA-256:<hex>`. A document key ends before the first `:` after its kind (ids hold
 * no `:`), so the encoding is injective.
 */
internal object RevisionRecordKeyEncoder : KeyEncoder<RevisionRecordKey> {
    override fun encode(key: RevisionRecordKey): String = "${SnapshotKeyEncoder.encode(key.document)}:${key.digest}"
}
