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

package me.ahoo.cosec.social

/**
 * Where to send the user to sign in ([url]), and the value that binds the sign-in to the browser that started it
 * ([binding]). Put [binding] in a cookie only that browser sends back to the callback ([setCookie]); the callback's
 * [SocialCredentials.binding] must carry it, or the callback is refused. Without the binding, a state issued to anyone
 * would sign in whoever opened a callback URL holding it: an attacker would sign a victim in to the attacker's account
 * (login CSRF, round-2 review M4).
 */
class SocialAuthorization(val url: String, val binding: String) {
    /**
     * The `Set-Cookie` header value for [binding]: `HttpOnly`, `Secure`, `SameSite=Lax` (the provider's redirect to the
     * callback is a top-level navigation, which `Lax` lets through) and no longer than the state lives.
     */
    fun setCookie(path: String = "/", name: String = BINDING_COOKIE): String =
        "$name=$binding; Path=$path; Max-Age=${STATE_LIFETIME_SECONDS}; HttpOnly; Secure; SameSite=Lax"

    override fun toString(): String = "SocialAuthorization($url)"

    companion object {
        /** The cookie [setCookie] names by default. */
        const val BINDING_COOKIE = "cosec_oauth_binding"

        /** How long a state, and its binding, can be used: three minutes. */
        const val STATE_LIFETIME_SECONDS = 180L
    }
}
