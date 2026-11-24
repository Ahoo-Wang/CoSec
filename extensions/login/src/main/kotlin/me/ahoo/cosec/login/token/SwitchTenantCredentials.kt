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

package me.ahoo.cosec.login.token

import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.login.LoginCredentials

/**
 * What a switch of tenant ([SwitchTenantAuthentication]) is presented with. The application's own class implements it,
 * and carries what else its [TenantSwitch] reads.
 */
interface SwitchTenantCredentials : LoginCredentials {
    val targetTenantId: String

    /**
     * The app the user asks to work in, if any. Only the [TenantSwitch] reads it: the new token is for the app the
     * switch answers, never for this one as such (`SCP-12`).
     */
    val targetAppId: String? get() = null

    /**
     * The context of the permitted request asking to switch, as the pipeline made it: its session, which CoSec's token
     * proved, is what the new token is issued from, with its login time (`IDN-22`).
     */
    val context: SecurityContext

    /**
     * The access token of the pair the switch presents: the one the request was made with ([context]'s credential). The
     * switch issues a session that refreshes, so it presents what a refresh presents: an access token alone does not
     * switch (`IDN-22`).
     */
    val accessToken: String

    /** The refresh token of that pair, which belongs to [accessToken], as a refresh's does. */
    val refreshToken: String
}
