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
package me.ahoo.cosec.spring.boot.starter.shadow

import me.ahoo.cosec.spring.boot.starter.ENABLED_SUFFIX_KEY
import me.ahoo.cosec.spring.boot.starter.EnabledCapable
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

/**
 * Shadow evaluation of candidate policies. Off by default.
 */
@ConfigurationProperties(ShadowProperties.PREFIX)
data class ShadowProperties(
    @DefaultValue("false") override var enabled: Boolean = false,
    /**
     * Resource patterns of the candidate policy files, one policy per file.
     */
    @DefaultValue(DEFAULT_LOCATION) var locations: Set<String> = setOf(DEFAULT_LOCATION),
    /**
     * Resource patterns of the candidate app permission files, one app permission per file.
     */
    @DefaultValue(DEFAULT_APP_PERMISSION_LOCATION)
    var appPermissionLocations: Set<String> = setOf(DEFAULT_APP_PERMISSION_LOCATION),
    /**
     * Share of requests evaluated against the candidates, from 0 to 1. Each costs a second evaluation.
     */
    @DefaultValue("1.0") var sampleRate: Double = 1.0,
    /**
     * Also report requests whose result is the same but decided by a different rule.
     */
    @DefaultValue("false") var reportRuleChanges: Boolean = false,
) : EnabledCapable {
    companion object {
        const val PREFIX: String = "cosec.shadow"
        const val ENABLED_KEY: String = PREFIX + ENABLED_SUFFIX_KEY
        const val DEFAULT_LOCATION = "classpath:cosec-shadow/*-policy.json"
        const val DEFAULT_APP_PERMISSION_LOCATION = "classpath:cosec-shadow/*-app-permission.json"
    }
}
