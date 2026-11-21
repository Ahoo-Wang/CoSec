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

package me.ahoo.cosec.control.sdk

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.CoSecJson
import tools.jackson.core.JacksonException

/**
 * A call [ControlService] refused (`CTL-65`), carrying the problem exactly as the control API's route answers it: its
 * [status] and its `application/problem+json` body, [problem], whose members are the route's (`type`, `title`,
 * `status`, `code`, `requestId`, and `detail` where the route gives one). [type], [code], [detail] and [requestId] are
 * read from it. A 401, 403, 404, 500 or 503 carries no detail, so a refusal never tells whether a document or a change
 * exists; a 400, 409, 410, 412, 422 or 428 explains the caller's own request.
 */
@ExperimentalCoSecApi
class ControlRefusedException(
    val status: Int,
    /** The problem's JSON text, as the route answered it. */
    val problem: String,
) : RuntimeException("The control plane refused the call: $status ${read(problem, "code") ?: ""}".trimEnd()) {
    /** The problem's `type` URI. */
    val type: String? get() = read(problem, "type")

    /** The problem's `code`, such as `FORBIDDEN` or `CONFLICT`. */
    val code: String? get() = read(problem, "code")

    /** The problem's `detail`; null when the route gives none. */
    val detail: String? get() = read(problem, "detail")

    /**
     * Which conflict a 409 is (`CTL-80`): [ConflictKind.BASE_MOVED] is read again and retried, [ConflictKind.SLOT_HELD]
     * waited out, the others stopped at. Null for any other status.
     */
    val conflict: ConflictKind? get() = read(problem, "conflict")?.let(ConflictKind::of)

    /**
     * The change that holds the slot a [ConflictKind.SLOT_HELD] wanted (the problem's `heldBy`, `CTL-80`), where the
     * control plane knows it; null for any other refusal.
     */
    val heldBy: String? get() = read(problem, "heldBy")

    /** The problem's `requestId`, which the control host's log names with why it refused. */
    val requestId: String? get() = read(problem, "requestId")

    private companion object {
        fun read(problem: String, member: String): String? = try {
            CoSecJson.mapper.readTree(problem).get(member)?.takeIf { it.isString }?.asString()
        } catch (_: JacksonException) {
            null
        }
    }
}
