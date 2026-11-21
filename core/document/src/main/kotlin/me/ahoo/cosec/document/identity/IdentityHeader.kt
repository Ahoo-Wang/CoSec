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

package me.ahoo.cosec.document.identity

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.identity.Identity

/**
 * The `CoSec-Identity` header, as a service writes it to call another service behind the gateway, or the control-plane
 * host's service port, as the principal it states: the format a gateway forwards (v1, `schema/8.0/cosec-identity.schema.json`),
 * which the receiving `IdentityGate` reads and holds to its expiry, maximum age, tenant and scope.
 *
 * The header carries no signature: whoever can reach the receiver can write one for anyone. It is for a network the
 * gateway and the named services alone can reach, which the receiver's statement says (`STA-7`); the receiver still
 * authorizes and records every call as the principal it names.
 */
@ExperimentalCoSecApi
object IdentityHeader {
    /** The header's name. */
    const val NAME: String = IdentityCodec.HEADER

    /**
     * The header value of [identity]. Throws [IllegalStateException] when the value would be longer than [maxBytes] (8 KiB
     * by default, the receiver's default limit), a claim is of a kind the header does not carry, or the credential never
     * expires; and [IllegalArgumentException] when a receiver would refuse what it wrote (an empty id, a time out of
     * range): a header written is a header read.
     */
    fun write(identity: Identity, maxBytes: Int = IdentityCodec.DEFAULT_MAX_BYTES): String =
        IdentityCodec.encode(identity, maxBytes)
}
