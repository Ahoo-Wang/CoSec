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

package me.ahoo.cosec.compat.side

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ChangeRequestCodec
import me.ahoo.cosec.document.shadow.ShadowCodec

/**
 * The control plane's and the shadow window's records (MIX-12 to MIX-15), as this version decodes and encodes them:
 * `record.rewrite` reads `value` as a record of `kind` and writes back what it read, which is what this version leaves
 * in the store after a read-modify-write; a record it refuses answers `refused:format`.
 */
internal object RecordOperations {
    val ALL: Map<String, (Map<String, String>) -> String> = mapOf(
        "record.rewrite" to { a ->
            val bytes = a.getValue("value").encodeToByteArray()
            when (a.getValue("kind")) {
                "change" -> ChangeRequestCodec.encode(ChangeRequestCodec.decode(bytes))
                "staging_slot" -> ChangeRequestCodec.encode(ChangeRequestCodec.decodeSlot(bytes))
                "candidate" -> ShadowCodec.encode(ShadowCodec.decodeCandidate(bytes))
                "shadow_report" -> ShadowCodec.encodeReport(ShadowCodec.decodeReport(bytes))
                "instance_status" -> ShadowCodec.encodeStatuses(ShadowCodec.decodeStatuses(bytes))
                "resident_documents" -> ShadowCodec.encodeResident(ShadowCodec.decodeResident(bytes))
                else -> throw IllegalArgumentException("no record kind")
            }.decodeToString()
        },
        // Whose the approval of a change record was (APR-9), as this version reads it: a release before kinds reads none.
        "record.approval.kind" to { a -> Kinds.approvalKind(a.getValue("value").encodeToByteArray()) },
    )
}
