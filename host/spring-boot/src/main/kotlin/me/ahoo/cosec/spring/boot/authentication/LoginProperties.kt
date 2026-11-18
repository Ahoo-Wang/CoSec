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
package me.ahoo.cosec.spring.boot.authentication

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spring.boot.CoSecProperties
import me.ahoo.cosec.spring.boot.EnabledCapable
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

/**
 * `cosec.authentication.login.*`: the login beans, which turn credentials into a token (`TokenCompositeAuthentication`,
 * your `Authentication` beans, social login). Authenticating requests in the pipeline is not switched by it.
 */
@ConfigurationProperties(prefix = LoginProperties.PREFIX)
@InternalCoSecApi
class LoginProperties(
    /** Whether the login beans are on, which turn credentials into a token; authenticating requests does not depend on it. */
    @DefaultValue("true") override var enabled: Boolean = true,
    /** The refresh route the starter serves (`IDN-23`): the pair in, a new pair of the session out. */
    @DefaultValue val refresh: ServedRoute = ServedRoute(),
    /** The logout route the starter serves (`IDN-24`): the pair in, the session ended. */
    @DefaultValue val logout: ServedRoute = ServedRoute(),
) : EnabledCapable {

    /** A route the starter serves under CoSec, as every route of the application: a document must permit it. */
    @InternalCoSecApi
    class ServedRoute(
        /**
         * Where the starter serves `POST` of this route, such as `/auth/refresh`. Unset (the default), it is not served
         * and the application serves its own.
         */
        var path: String? = null,
    )

    @InternalCoSecApi
    companion object {
        const val PREFIX = CoSecProperties.PREFIX + ".authentication.login"
        const val REFRESH_PATH_KEY = "$PREFIX.refresh.path"
        const val LOGOUT_PATH_KEY = "$PREFIX.logout.path"
    }
}
