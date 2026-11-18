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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.control.authz

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Action

/** The control-plane actions (RFC 0007 §7.1): `cosec:<verb>`. */
@InternalCoSecApi
object ControlActions {
    const val SERVICE: String = "cosec"
    val READ: Action = Action(SERVICE, "read")
    val SIMULATE: Action = Action(SERVICE, "simulate")
    val EXPLAIN: Action = Action(SERVICE, "explain")
    val ANALYZE: Action = Action(SERVICE, "analyze")
    val SHADOW_READ: Action = Action(SERVICE, "shadow-read")
    val WRITE: Action = Action(SERVICE, "write")
    val GRANT: Action = Action(SERVICE, "grant")
    val PUBLISH: Action = Action(SERVICE, "publish")
    val SKIP_SHADOW: Action = Action(SERVICE, "skip-shadow")
    val APPROVE: Action = Action(SERVICE, "approve")
    val UNPROTECT: Action = Action(SERVICE, "unprotect")
    val BREAKGLASS: Action = Action(SERVICE, "breakglass")

    /** Setting a tenant's boundary or the platform's default (RFC 0007 §7.6); only the platform holds it. */
    val BOUNDARY: Action = Action(SERVICE, "boundary")
}
