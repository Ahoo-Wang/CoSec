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

const val CONDITION_MATCHER_PART_KEY = "part"

fun interface PartExtractor {
    fun extract(request: Request, securityContext: SecurityContext): String
}

object RequestParts {
    const val PREFIX = "request."
    const val PATH = PREFIX + "path"
    const val METHOD = PREFIX + "method"
    const val REMOTE_IP = PREFIX + "remoteIp"
    const val ORIGIN = PREFIX + "origin"
    const val REFERER = PREFIX + "referer"
    const val HEADER_PREFIX = PREFIX + "header."
    const val ATTRIBUTES_PREFIX = PREFIX + "attributes."
}

object SecurityContextParts {
    const val PREFIX = "context."
    const val TENANT_ID = PREFIX + "tenantId"
    const val PRINCIPAL_PREFIX = PREFIX + "principal."
    const val PRINCIPAL_ID = PRINCIPAL_PREFIX + "id"
    const val PRINCIPAL_ATTRIBUTES_PREFIX = PRINCIPAL_PREFIX + "attributes."
}

data class DefaultPartExtractor(val part: String) : PartExtractor {

    override fun extract(request: Request, securityContext: SecurityContext): String {
        return when (part) {
            RequestParts.PATH -> request.path
            RequestParts.METHOD -> request.method
            RequestParts.REMOTE_IP -> request.remoteIp
            RequestParts.ORIGIN -> request.origin
            RequestParts.REFERER -> request.referer
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
                    return securityContext.principal.attributes[headerKey].orEmpty()
                }

                throw IllegalArgumentException("Unsupported part: $part")
            }
        }
    }
}
