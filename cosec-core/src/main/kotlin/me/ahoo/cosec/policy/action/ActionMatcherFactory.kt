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

package me.ahoo.cosec.policy.action

import me.ahoo.cosec.api.configuration.Configuration
import me.ahoo.cosec.api.policy.ActionMatcher

/**
 * Factory interface for creating [ActionMatcher] instances.
 *
 * Implementations are responsible for creating action matchers
 * based on configuration. The factory is registered with
 * [ActionMatcherFactoryProvider] to make matchers available
 * for policy definitions.
 *
 * @see ActionMatcher
 * @see ActionMatcherFactoryProvider
 */
interface ActionMatcherFactory {
    /** The type identifier for this factory */
    val type: String

    /**
     * Creates an action matcher from the given configuration.
     *
     * @param configuration The configuration for the matcher
     * @return A new ActionMatcher instance
     */
    fun create(configuration: Configuration): ActionMatcher
}
