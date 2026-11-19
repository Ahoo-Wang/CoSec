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

package me.ahoo.cosec.runtime.attribute

import me.ahoo.cosec.annotations.CoSecSpi
import me.ahoo.cosec.annotations.InternalCoSecApi

/**
 * Where the blacklist comes from (RFC 0006 §5.4.4): whether [value] of [kind] is blocked. A failed lookup throws; it
 * is never "not blocked", so the request is a 503 unless a DENY matches. Like every SPI of the pipeline, it must not
 * block the calling thread. An address ([BlacklistKind.IP]) is asked for in its canonical form: a source keeps the
 * addresses it blocks in that form too.
 */
@CoSecSpi
fun interface BlacklistSource {
    suspend fun contains(kind: BlacklistKind, value: String): Boolean
}

enum class BlacklistKind(val attribute: String) {
    /** An authenticated principal, by `<tenant>:<principal id>`: principal ids are unique only within a tenant. */
    PRINCIPAL(BlacklistAttributeProvider.PRINCIPAL),

    /**
     * The client address, canonical (R1A-4): RFC 5952 for IPv6 (lower case, the longest run of zero groups as `::`), an
     * IPv4-mapped IPv6 address as its IPv4 address, dotted decimal for IPv4: `2001:db8::1`, `192.0.2.1`.
     */
    IP(BlacklistAttributeProvider.IP),

    /** The `CoSec-Device-Id` of the request. */
    DEVICE(BlacklistAttributeProvider.DEVICE),
    ;

    companion object {
        /** The value [kind] blocks for a principal of [tenant]. */
        fun principal(tenant: String, principal: String): String = "$tenant:$principal"
    }
}
