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

package me.ahoo.cosec.migrate

import me.ahoo.cosec.migrate.v7.api.context.request.Request
import java.net.URI

/** 7.x's request for evaluating policies offline, as `cosec-core` had it: the 7.x side of [Facts]. */
data class EvaluateRequest(
    override val path: String = "/policies/test",
    override val method: String = "POST",
    override val remoteIp: String = "127.0.0.1",
    override val origin: URI = URI.create("http://mockOrigin"),
    override val referer: URI = URI.create("http://mockReferer"),
    override val attributes: Map<String, String> = mapOf(),
    private val headers: Map<String, String> = mapOf(),
    private val queries: Map<String, String> = mapOf(),
    private val cookies: Map<String, String> = mapOf(),
) : Request {

    override fun getHeader(key: String): String {
        return headers[key].orEmpty()
    }

    override fun getQuery(key: String): String {
        return queries[key].orEmpty()
    }

    override fun getCookieValue(key: String): String {
        return cookies[key].orEmpty()
    }

    override fun withAttributes(attributes: Map<String, String>): Request {
        return copy(attributes = attributes)
    }
}
