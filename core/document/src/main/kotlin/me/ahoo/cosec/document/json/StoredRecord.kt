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

package me.ahoo.cosec.document.json

import me.ahoo.cosec.annotations.InternalCoSecApi

/**
 * The kinds of record CoSec stores and reads by [StoredFormat]'s rule (`SNP-27`): the closed set of the `record` tag of
 * `cosec.stored.refused` (`OBS-12`), so a tag never carries anything a record holds.
 */
@InternalCoSecApi
enum class StoredRecord(val tag: String) {
    SESSION("session"),
    CHANGE("change"),
    STAGING_SLOT("staging_slot"),
    REVISION_INDEX("revision_index"),
    APP_LIST("app_list"),
    CANDIDATE("candidate"),
    SHADOW_REPORT("shadow_report"),
    INSTANCE_STATUS("instance_status"),
    INSTANCE_INDEX("instance_index"),
    REPORTERS("reporters"),
}
