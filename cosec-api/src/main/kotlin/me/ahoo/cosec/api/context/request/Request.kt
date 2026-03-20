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

package me.ahoo.cosec.api.context.request

import me.ahoo.cosec.api.context.Attributes
import me.ahoo.cosec.api.context.request.AppIdCapable.Companion.APP_ID_KEY
import me.ahoo.cosec.api.context.request.DeviceIdCapable.Companion.DEVICE_ID_KEY
import me.ahoo.cosec.api.context.request.RequestIdCapable.Companion.REQUEST_ID_KEY
import me.ahoo.cosec.api.context.request.SpaceIdCapable.Companion.SPACE_ID_KEY
import java.net.URI

/**
 * Represents an incoming request that needs to be authorized.
 *
 * This interface provides access to all request information needed for
 * authorization decisions, including:
 * - Request metadata (path, method, headers)
 * - Application and tenant identifiers
 * - Device and request tracking IDs
 * - Custom attributes
 *
 * @see SecurityContext
 * @see Authorization
 */
interface Request :
    Attributes<Request, String, String>,
    AppIdCapable,
    SpaceIdCapable,
    DeviceIdCapable,
    RequestIdCapable {
    /**
     * The application ID, resolved from header or query parameter.
     *
     * @see AppIdCapable.APP_ID_KEY
     */
    override val appId: AppId
        get() {
            return getHeader(APP_ID_KEY).ifBlank { getQuery(APP_ID_KEY) }
        }

    /**
     * The space/tenant ID, resolved from header or query parameter.
     *
     * @see SpaceIdCapable.SPACE_ID_KEY
     */
    override val spaceId: SpaceId
        get() {
            return getHeader(SPACE_ID_KEY).ifBlank { getQuery(SPACE_ID_KEY) }
        }

    /**
     * The device ID, resolved from header or query parameter.
     *
     * @see DeviceIdCapable.DEVICE_ID_KEY
     */
    override val deviceId: DeviceId
        get() {
            return getHeader(DEVICE_ID_KEY).ifBlank { getQuery(DEVICE_ID_KEY) }
        }

    /**
     * The request ID for tracing, resolved from header.
     *
     * @see RequestIdCapable.REQUEST_ID_KEY
     */
    override val requestId: RequestId
        get() {
            return getHeader(REQUEST_ID_KEY)
        }

    /** The request path (e.g., "/api/users/123") */
    val path: String

    /** The HTTP method (e.g., "GET", "POST", "PUT", "DELETE") */
    val method: String

    /** The remote IP address of the client */
    val remoteIp: String

    /** The origin header value */
    val origin: URI

    /** The referer header value */
    val referer: URI

    /**
     * Gets a header value by key.
     *
     * @param key The header name
     * @return The header value, or empty string if not found
     */
    fun getHeader(key: String): String

    /**
     * Gets a query parameter value by key.
     *
     * @param key The parameter name
     * @return The parameter value, or empty string if not found
     */
    fun getQuery(key: String): String

    /**
     * Gets a cookie value by name.
     *
     * @param name The cookie name
     * @return The cookie value, or empty string if not found
     */
    fun getCookieValue(name: String): String
}
