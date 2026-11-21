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

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.http.RequestSecurity

/**
 * How the operator of a control-plane step was identified (`CTL-57`), recorded with the step so that an audit reads who
 * was proven and who was only said: [asserted] is true when the host believed the identity the caller stated (the
 * `CoSec-Identity` header, `STA-9`) instead of verifying a credential itself; [via] is the credential as it was stated
 * or verified, never its value; [peer] is the address of the connection the request arrived on, which the caller does
 * not choose (the client address a request states is the caller's). A change records the origin of its author, and each
 * event that of the step's operator; a step no request drives (a maintenance pass) has none.
 */
@ExperimentalCoSecApi
class ChangeOrigin(
    val asserted: Boolean,
    val via: Via? = null,
    val peer: String? = null,
) {
    /** The credential of the request: its [method] (`jwt`, `api-key`, ...), its id and the request's own id. */
    class Via(val method: String, val credentialId: String?, val requestId: String?) {
        override fun equals(other: Any?): Boolean = other is Via && method == other.method &&
            credentialId == other.credentialId && requestId == other.requestId

        override fun hashCode(): Int = arrayOf(method, credentialId, requestId).contentHashCode()

        override fun toString(): String = "Via($method, credential=$credentialId, request=$requestId)"
    }

    override fun equals(other: Any?): Boolean = other is ChangeOrigin && asserted == other.asserted &&
        via == other.via && peer == other.peer

    override fun hashCode(): Int = arrayOf(asserted, via, peer).contentHashCode()

    override fun toString(): String = "ChangeOrigin(asserted=$asserted, via=$via, peer=$peer)"

    @InternalCoSecApi
    companion object {
        /** The origin of a permitted request: asserted when the host's gate believed the identity it states. */
        fun of(security: RequestSecurity, peer: String? = null): ChangeOrigin {
            val identity = security.identity
            val credential = identity?.credential ?: security.principal?.credential
            return ChangeOrigin(
                asserted = identity != null,
                via = credential?.let { Via(it.method.value, it.id, security.requestId) },
                peer = peer,
            )
        }
    }
}
