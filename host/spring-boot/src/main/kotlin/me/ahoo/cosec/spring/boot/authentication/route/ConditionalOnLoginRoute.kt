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

package me.ahoo.cosec.spring.boot.authentication.route

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spring.boot.authentication.LoginProperties
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.ConfigurationCondition

/** When the starter serves a login route: `cosec.authentication.login.refresh.path` or `logout.path` is set. */
@Conditional(OnLoginRoute::class)
internal annotation class ConditionalOnLoginRoute

internal class OnLoginRoute : AnyNestedCondition(ConfigurationCondition.ConfigurationPhase.REGISTER_BEAN) {
    @ConditionalOnProperty(LoginProperties.REFRESH_PATH_KEY)
    class Refresh

    @ConditionalOnProperty(LoginProperties.LOGOUT_PATH_KEY)
    class Logout
}
