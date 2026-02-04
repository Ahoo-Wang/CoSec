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
import me.ahoo.cosec.api.context.request.AppIdCapable.Companion.LEGACY_APP_ID_KEY
import me.ahoo.cosec.api.context.request.DeviceIdCapable.Companion.DEVICE_ID_KEY
import me.ahoo.cosec.api.context.request.DeviceIdCapable.Companion.LEGACY_DEVICE_ID_KEY
import me.ahoo.cosec.api.context.request.RequestIdCapable.Companion.REQUEST_ID_KEY
import me.ahoo.cosec.api.context.request.SpaceIdCapable.Companion.SPACE_ID_KEY
import java.net.URI

interface Request :
    Attributes<Request, String, String>,
    AppIdCapable,
    SpaceIdCapable,
    DeviceIdCapable,
    RequestIdCapable {

    override val appId: AppId
        get() {
            return getHeaderOrQuery(APP_ID_KEY, LEGACY_APP_ID_KEY)
        }
    override val spaceId: SpaceId
        get() {
            return getHeaderOrQuery(SPACE_ID_KEY)
        }
    override val deviceId: DeviceId
        get() {
            return getHeaderOrQuery(DEVICE_ID_KEY, LEGACY_DEVICE_ID_KEY)
        }
    override val requestId: RequestId
        get() {
            return getHeader(REQUEST_ID_KEY)
        }

    private fun getHeaderOrQuery(key: String, fallbackKey: String): String {
        return getHeaderOrQuery(key).ifBlank {
            getHeaderOrQuery(fallbackKey)
        }
    }

    private fun getHeaderOrQuery(key: String): String {
        return getHeader(key).ifBlank { getQuery(key) }
    }

    val path: String
    val method: String
    val remoteIp: String
    val origin: URI
    val referer: URI
    fun getHeader(key: String): String
    fun getQuery(key: String): String
    fun getCookieValue(key: String): String
}
