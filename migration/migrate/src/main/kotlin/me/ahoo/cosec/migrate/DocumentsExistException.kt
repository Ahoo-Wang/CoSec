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

package me.ahoo.cosec.migrate

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/** 8.0 documents already exist for [keys] (and, with resume, differ from the converted ones): never overwritten. */
class DocumentsExistException(val keys: List<SnapshotKey>, written: List<SnapshotKey>, differ: Boolean = false) :
    PublishException(
        if (differ) {
            "8.0 documents for $keys exist and differ from the converted ones; publish never overwrites."
        } else {
            "8.0 documents already exist for $keys; publish never overwrites (--resume skips identical ones)."
        },
        written,
    )
