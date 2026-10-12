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
package me.ahoo.cosec.explain

import me.ahoo.cosec.api.context.request.AppIdCapable
import me.ahoo.cosec.api.context.request.DeviceIdCapable
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.context.request.SpaceIdCapable
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import me.ahoo.cosec.tenant.SimpleTenant
import java.net.URI

/**
 * The principal to explain a decision for, described directly rather than taken from a token.
 *
 * @param tenantId the principal's tenant; null keeps the default tenant
 */
data class ExplainPrincipal(
    val id: String,
    val tenantId: String? = null,
    val roles: Set<String> = emptySet(),
    val policies: Set<String> = emptySet(),
    val attributes: Map<String, Any> = emptyMap()
) {
    fun toPrincipal(): CoSecPrincipal {
        val principal = SimplePrincipal(id = id, policies = policies, roles = roles, attributes = attributes)
        return tenantId?.let { SimpleTenantPrincipal(principal, SimpleTenant(it)) } ?: principal
    }
}

/**
 * The request to explain. [appId], [spaceId] and [deviceId] become the corresponding `CoSec-*` headers.
 */
data class ExplainRequest(
    val path: String,
    val method: String = "GET",
    val remoteIp: String = "127.0.0.1",
    val appId: String = "",
    val spaceId: String = "",
    val deviceId: String = "",
    val origin: String = "",
    val referer: String = "",
    val headers: Map<String, String> = emptyMap(),
    val query: Map<String, String> = emptyMap(),
    val cookies: Map<String, String> = emptyMap(),
    val attributes: Map<String, String> = emptyMap()
) {
    fun toRequest(): Request {
        val cosecHeaders = mapOf(
            AppIdCapable.APP_ID_KEY to appId,
            SpaceIdCapable.SPACE_ID_KEY to spaceId,
            DeviceIdCapable.DEVICE_ID_KEY to deviceId,
        ).filterValues { it.isNotEmpty() }
        return EvaluateRequest(
            path = path,
            method = method,
            remoteIp = remoteIp,
            origin = URI.create(origin),
            referer = URI.create(referer),
            attributes = attributes,
            // HTTP header names are case-insensitive: "Authorization" must reach a parser reading "authorization".
            headers = (headers + cosecHeaders).toSortedMap(String.CASE_INSENSITIVE_ORDER),
            queries = query,
            cookies = cookies,
        )
    }
}
