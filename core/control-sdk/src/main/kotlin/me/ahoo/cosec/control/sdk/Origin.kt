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

package me.ahoo.cosec.control.sdk

import me.ahoo.cosec.annotations.ExperimentalCoSecApi

/**
 * Where a change's author called from (`origin`, `CTL-57`): whether a gateway [asserted] the identity, the [peer]
 * address, and how the call came in ([method]: `in-process`, a token, an API key; with its [credential] and [request]
 * ids). Only a reader given the author's whole origin reads every member (`CTL-70`); the others are null.
 */
@ExperimentalCoSecApi
class Origin(
    val asserted: Boolean,
    val peer: String? = null,
    val method: String? = null,
    val credential: String? = null,
    val request: String? = null,
) {
    override fun equals(other: Any?): Boolean = other is Origin && parts() == other.parts()

    override fun hashCode(): Int = parts().hashCode()

    override fun toString(): String = describe(
        "Origin",
        "asserted" to asserted,
        "peer" to peer,
        "method" to method,
        "credential" to credential,
        "request" to request,
    )

    private fun parts(): List<Any?> = listOf(asserted, peer, method, credential, request)
}
