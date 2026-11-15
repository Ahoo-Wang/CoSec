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

package me.ahoo.cosec.runtime

import me.ahoo.cosec.annotations.ExperimentalCoSecApi

/**
 * The body of an outcome that does not proceed: an RFC 9457 problem (RFC 0006 §6.2). It holds what a client can act on
 * and nothing more: no policy, statement, binding, cause or exception (P16).
 */
@ExperimentalCoSecApi
class Problem internal constructor(
    val type: ProblemType,
    /** Equals the `CoSec-Request-Id` response header. */
    val requestId: String,
    /** The trace id captured at the bridge, when tracing is on; else absent from the body. */
    val traceId: String?,
) {
    val status: Int get() = type.status

    override fun toString(): String = "Problem(type=${type.slug}, requestId=$requestId, traceId=$traceId)"
}
