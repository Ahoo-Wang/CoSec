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
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * `cosec.authentication.token-cache.*`: the tokens CoSec's own verifier verified, kept for the requests that carry them
 * again (R2B-22, `IDN-32`). Never for an application's own `TokenVerifier`. It holds the token cache's share of the heap
 * (`HeapBudget`, `STA-12`), each entry weighed by its token's length.
 */
@ConfigurationProperties(prefix = TokenCacheProperties.PREFIX)
@InternalCoSecApi
class TokenCacheProperties {
    /**
     * Whether a request's token, verified once by CoSec's own verifier, is answered from memory until it expires instead
     * of verified again. The revocation and reserved-attribute checks run on every request either way.
     */
    var enabled: Boolean = true

    @InternalCoSecApi
    companion object {
        const val PREFIX = CoSecProperties.PREFIX + ".authentication.token-cache"
        const val ENABLED_KEY = "$PREFIX.enabled"
    }
}
