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

package me.ahoo.cosec.control.api

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.TenantId

/**
 * A request to decide without making it (RFC 0007 §10.3): who asks, with which memberships, in which scope, for which
 * action and resource, with which headers and request attributes. It describes the principal, so any principal of the
 * caller's reach can be simulated.
 */
@InternalCoSecApi
data class SimulatedRequest(
    val tenant: TenantId,
    val principal: String?,
    val roles: List<String> = emptyList(),
    val policies: List<String> = emptyList(),
    val app: String = "",
    val space: String = "",
    val action: Action,
    val resource: Resource,
    val remoteIp: String = "",
    val time: Long,
    /** The request's headers by name, each with its values in order: what `http.*` conditions read. */
    val headers: Map<String, List<String>> = emptyMap(),
    /** What the host knows besides HTTP, such as a gateway's route id: what `request.attributes.*` conditions read. */
    val requestAttributes: Map<String, String> = emptyMap(),
    /**
     * False for a credential with a role cap: what is bound to its principal by name grants it nothing, and its DENYs
     * still apply (RFC 0009 §4.2, RC1-1). A token by default.
     */
    val principalBindings: Boolean = true,
    /** False for an API key or any credential but CoSec's own token: break-glass does not apply. A token by default. */
    val breakGlass: Boolean = true,
)
