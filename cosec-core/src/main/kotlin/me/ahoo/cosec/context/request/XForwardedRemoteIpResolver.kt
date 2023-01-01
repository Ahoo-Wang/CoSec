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

package me.ahoo.cosec.context.request

import org.slf4j.LoggerFactory

/**
 * Refer to `org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver`
 */
abstract class XForwardedRemoteIpResolver<R>(
    private val defaultRemoteIpResolver: RemoteIpResolver<R>
) :
    RemoteIpResolver<R> {
    companion object {
        const val X_FORWARDED_FOR = "X-Forwarded-For"
        const val DELIMITER = ','
        private val log = LoggerFactory.getLogger(XForwardedRemoteIpResolver::class.java)
    }

    open val maxTrustedIndex: Int
        get() = Int.MAX_VALUE

    override fun resolve(request: R): String? {
        val xForwardedHeaderValues: List<String>? = extractXForwardedHeaderValues(request)
        if (xForwardedHeaderValues.isNullOrEmpty()) {
            return defaultRemoteIpResolver.resolve(request)
        }

        if (xForwardedHeaderValues.size > 1) {
            if (log.isWarnEnabled) {
                log.warn("Multiple X-Forwarded-For headers found, discarding all")
            }
            return defaultRemoteIpResolver.resolve(request)
        }

        val xForwardedValues = xForwardedHeaderValues[0]
            .split(DELIMITER)
            .dropWhile { it.isBlank() }
            .reversed()
        if (xForwardedValues.isEmpty()) {
            return defaultRemoteIpResolver.resolve(request)
        }
        val index = xForwardedValues.size.coerceAtMost(maxTrustedIndex) - 1
        return xForwardedValues[index]
    }

    abstract fun extractXForwardedHeaderValues(request: R): List<String>?
}
