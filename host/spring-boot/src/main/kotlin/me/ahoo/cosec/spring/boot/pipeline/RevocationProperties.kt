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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.spring.boot.pipeline

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spring.boot.CoSecProperties
import me.ahoo.cosec.spring.boot.authorization.cache.CacheConfiguration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.NestedConfigurationProperty
import java.time.Duration

/** `cosec.authentication.revocation.*` (RFC 0004 §4.5.3). */
@ConfigurationProperties(prefix = RevocationProperties.PREFIX)
@InternalCoSecApi
class RevocationProperties {
    /**
     * How much later than a subject revocation a token may have been issued and still be revoked by it: room for an
     * issuer whose clock runs ahead of the revoking one.
     */
    var skew: Duration = Duration.ofSeconds(5)

    /**
     * Whether a request's token is refused (503) while the revocations cannot be read, instead of accepted: the
     * documented exception to failing closed, which applies to verifying a token only. Refresh always fails then.
     */
    var failClosed: Boolean = false

    /**
     * How long a check's two revocation reads may take together: past it the store is unavailable, so a request's token
     * is accepted (or refused, with `fail-closed`) and a refresh fails. Positive, and less than
     * `cosec.pipeline.authentication-timeout`, or a hanging store would make every authenticated request 503.
     */
    var readTimeout: Duration = Duration.ofMillis(300)

    /** The local tier of the token and subject revocation caches. */
    @NestedConfigurationProperty
    @InternalCoSecApi
    var cache: CacheConfiguration = CacheConfiguration()

    @InternalCoSecApi
    companion object {
        const val PREFIX = CoSecProperties.PREFIX + ".authentication.revocation"
    }
}
