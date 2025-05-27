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

package me.ahoo.cosec.policy.condition.part

import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.policy.action.getPathVariables

const val CONDITION_MATCHER_PART_KEY = "part"

fun interface PartExtractor {
    fun extract(request: Request, securityContext: SecurityContext): String
}

object RequestParts {
    const val PREFIX = "request."
    const val PATH = PREFIX + "path"
    const val METHOD = PREFIX + "method"
    const val APP_ID = PREFIX + "appId"
    const val DEVICE_ID = PREFIX + "deviceId"
    const val REMOTE_IP = PREFIX + "remoteIp"
    const val ORIGIN = PREFIX + "origin"
    const val ORIGIN_HOST = "$ORIGIN.host"
    const val REFERER = PREFIX + "referer"
    const val HEADER_PREFIX = PREFIX + "header."
    const val ATTRIBUTES_PREFIX = PREFIX + "attributes."
    const val PATH_VAR_PREFIX = "$PATH.var."
}

object SecurityContextParts {
    const val PREFIX = "context."
    const val TENANT_ID = PREFIX + "tenantId"
    const val PRINCIPAL_PREFIX = PREFIX + "principal."
    const val PRINCIPAL_ID = PRINCIPAL_PREFIX + "id"
    const val PRINCIPAL_ATTRIBUTES_PREFIX = PRINCIPAL_PREFIX + "attributes."
}

data class DefaultPartExtractor(val part: String) : PartExtractor {

    @Suppress("CyclomaticComplexMethod")
    override fun extract(request: Request, securityContext: SecurityContext): String {
        return when (part) {
            RequestParts.PATH -> request.path
            RequestParts.METHOD -> request.method
            RequestParts.APP_ID -> request.appId
            RequestParts.DEVICE_ID -> request.deviceId
            RequestParts.REMOTE_IP -> request.remoteIp
            RequestParts.ORIGIN -> request.origin.toString()
            RequestParts.ORIGIN_HOST -> request.origin.host.orEmpty()
            RequestParts.REFERER -> request.referer.toString()
            SecurityContextParts.TENANT_ID -> securityContext.tenant.tenantId
            SecurityContextParts.PRINCIPAL_ID -> securityContext.principal.id
            else -> {
                if (part.startsWith(RequestParts.HEADER_PREFIX)) {
                    val headerKey = part.substring(RequestParts.HEADER_PREFIX.length)
                    return request.getHeader(headerKey)
                }
                if (part.startsWith(RequestParts.ATTRIBUTES_PREFIX)) {
                    val attributeKey = part.substring(RequestParts.ATTRIBUTES_PREFIX.length)
                    return request.attributes[attributeKey].orEmpty()
                }
                if (part.startsWith(SecurityContextParts.PRINCIPAL_ATTRIBUTES_PREFIX)) {
                    val headerKey = part.substring(SecurityContextParts.PRINCIPAL_ATTRIBUTES_PREFIX.length)
                    return securityContext.principal.attributes[headerKey]?.toString().orEmpty()
                }
                if (part.startsWith(RequestParts.PATH_VAR_PREFIX)) {
                    val pathVarKey = part.substring(RequestParts.PATH_VAR_PREFIX.length)
                    return securityContext.getPathVariables()?.get(pathVarKey).orEmpty()
                }
                throw IllegalArgumentException("Unsupported part: $part")
            }
        }
    }
}
