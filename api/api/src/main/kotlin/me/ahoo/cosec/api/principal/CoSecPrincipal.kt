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

package me.ahoo.cosec.api.principal

import me.ahoo.cosec.api.CoSec
import java.security.Principal

/**
 * Who the current request is, as an application reads it from a `SecurityContext`: a read-only view of what the
 * pipeline decided on. The roles effective for the request are in `SecurityContext.scope`. A [Principal], as WebFlux's
 * `ServerWebExchange.getPrincipal` hands it to the application.
 */
interface CoSecPrincipal : Principal {
    /** The principal's id; [ANONYMOUS_ID] for a request without a valid credential. */
    val id: String

    override fun getName(): String = id

    /** Whether a credential proved this principal. Stated by the pipeline, never read off [id]. */
    val authenticated: Boolean

    /** What the credential says about the principal: a token's `attributes` claim, for example. */
    val attributes: Map<String, Any>

    companion object {
        /**
         * The id of an anonymous principal. No credential can claim it: an id beginning with `(` is CoSec's, and a token
         * with such a subject is invalid (`IDN-12`).
         */
        const val ANONYMOUS_ID = CoSec.DEFAULT
    }
}
