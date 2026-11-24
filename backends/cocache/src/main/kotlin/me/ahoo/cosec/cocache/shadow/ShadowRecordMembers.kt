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

package me.ahoo.cosec.cocache.shadow

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.StoredMembers
import me.ahoo.cosec.document.json.StoredMembers.Companion.opaque
import me.ahoo.cosec.document.json.StoredMembers.Companion.required
import me.ahoo.cosec.document.json.StoredRecord

/**
 * Every member of the instance index ([InstanceIndexCodec]) and of a window's reporters ([ReportersCodec]), `SNP-28`.
 * Each is read by every 8.x reader; the forms before formats (a bare map, a bare array) carry no members.
 */
internal object ShadowRecordMembers {
    val INSTANCE_INDEX = StoredMembers(StoredRecord.INSTANCE_INDEX, StoredMembers.COMMON + listOf(opaque("instances")))

    val REPORTERS = StoredMembers(StoredRecord.REPORTERS, StoredMembers.COMMON + listOf(required("reporters")))
}
