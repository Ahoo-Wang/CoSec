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

package me.ahoo.cosec.runtime.http

import me.ahoo.cosec.annotations.InternalCoSecApi

/** How a client address is written in CoSec: as the address alone, whatever a proxy or a server added around it. */
@InternalCoSecApi
object ClientAddress {
    /**
     * [text] trimmed, without a port, brackets or a zone: `192.0.2.1:8080` is `192.0.2.1`, `[2001:db8::1]:443` and
     * `[2001:db8::1]` are `2001:db8::1`, `fe80::1%en0` is `fe80::1`. Anything else as it is.
     */
    fun of(text: String): String {
        var address = text.trim()
        if (address.startsWith('[')) {
            val end = address.indexOf(']')
            if (end > 0) address = address.substring(1, end)
        } else if (address.count { it == ':' } == 1) {
            address = address.substringBefore(':')
        }
        if (':' in address) {
            address = address.substringBefore('%')
        }
        return address
    }
}
