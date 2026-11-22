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

package me.ahoo.cosec.runtime.identity

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.identity.Identity
import java.time.Duration
import java.time.Instant

/**
 * The identity a service states for itself when it calls the control plane as its own principal (`ControlCaller.service`,
 * `CTL-65`, `CTL-77`): a principal of the platform tenant, in the control plane's app, with the roles it states, behind a
 * credential that lives one call. The in-process implementation hands it to the gate as a forwarded identity; the HTTP
 * one writes it into `CoSec-Identity`: one identity, so the control plane decides and records the same call the same.
 */
@InternalCoSecApi
object ServiceIdentity {
    /** How long a service caller's identity lives: one call's, well under the gate's maximum age. */
    val LIFETIME: Duration = Duration.ofSeconds(30)

    /** The credential id of service [principal]'s identity: `service:<principal>`, what the record names. */
    fun credentialId(principal: String): String = "service:$principal"

    /** Service [principal]'s identity with [roles], in [app], made at [now] for request [requestId]. */
    fun of(principal: String, roles: Set<String>, app: String, now: Instant, requestId: String): Identity = Identity(
        tenant = TenantId.PLATFORM.value,
        subject = principal,
        app = app,
        space = "",
        roles = roles,
        credential = Identity.Credential(Identity.Method.API_KEY, credentialId(principal), now.plus(LIFETIME)),
        issuedAt = now,
        requestId = requestId,
    )
}
