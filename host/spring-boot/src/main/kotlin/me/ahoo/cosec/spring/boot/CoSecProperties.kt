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
package me.ahoo.cosec.spring.boot

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.DEFAULT_KEY_PREFIX
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

internal const val ENABLED_SUFFIX_KEY = ".enabled"

/**
 * CoSec Properties .
 *
 * @author ahoo wang
 */
@ConfigurationProperties(prefix = CoSecProperties.PREFIX)
@InternalCoSecApi
class CoSecProperties(
    @DefaultValue("true") override var enabled: Boolean = true,
    /**
     * The prefix of every Redis key this deployment writes (`SNP-9`, RC1-46): snapshot documents, control-plane
     * records, shadow reports, token revocations, memberships (`<p>:snapshot:…`, `<p>:control:…`, `<p>:token:…`, …),
     * API keys (`<p>:api-key:…`) and rate limit counters (`<p>:rate-limiter:…`). Two deployments on one Redis that differ
     * in it share nothing. A trailing `:` is ignored.
     */
    // The stores' own default: one definition, so the property's default and the keys the stores write agree. A const,
    // inlined here, so `cosec-cocache` stays an optional dependency.
    @DefaultValue(DEFAULT_KEY_PREFIX) var keyPrefix: String = DEFAULT_KEY_PREFIX,
) : EnabledCapable {
    /** The prefix of one family of this deployment's keys: `<keyPrefix>:<family>`. */
    fun keysOf(family: String): String = "${keyPrefix.trimEnd(':')}:$family"

    @InternalCoSecApi
    companion object {
        /** The prefix of every `cosec.*` property. */
        const val PREFIX = "cosec"
    }
}
