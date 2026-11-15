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
 * block the calling thread.
 */
@CoSecSpi
fun interface BlacklistSource {
    suspend fun contains(kind: BlacklistKind, value: String): Boolean
}

enum class BlacklistKind(val attribute: String) {
    /** An authenticated principal, by `<tenant>:<principal id>`: principal ids are unique only within a tenant. */
    PRINCIPAL(BlacklistAttributeProvider.PRINCIPAL),

    /** The client address, as the adapter reports it. */
    IP(BlacklistAttributeProvider.IP),

    /** The `CoSec-Device-Id` of the request. */
    DEVICE(BlacklistAttributeProvider.DEVICE),
    ;

    companion object {
        /** The value [kind] blocks for a principal of [tenant]. */
        fun principal(tenant: String, principal: String): String = "$tenant:$principal"
    }
}
