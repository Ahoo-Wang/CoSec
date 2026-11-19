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

package me.ahoo.cosec.system.auth

import me.ahoo.cosec.api.token.CompositeToken
import me.ahoo.cosec.login.token.TokenCompositeAuthentication
import me.ahoo.cosec.login.token.TokenConverter
import me.ahoo.cosec.login.token.TokenFamilies
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

/**
 * `POST /auth/login` with `{"username", "tenant", "app", "roles"}`: a token pair in a new session, issued by CoSec's
 * [TokenCompositeAuthentication]. With `"accessSeconds"` the access token lasts that long, which may be negative: a pair
 * whose access token has already expired and whose session and refresh token are live, what a client holds when its token
 * ran out, with nothing to wait for. Refresh and logout are the starter's own routes (`cosec.authentication.login.*.path`).
 */
@RestController
class Login(
    private val authentication: TokenCompositeAuthentication,
    private val families: TokenFamilies,
    private val converter: TokenConverter,
) {
    @PostMapping("/auth/login")
    suspend fun login(@RequestBody body: Map<String, Any?>): CompositeToken {
        val roles = (body["roles"] as List<*>?).orEmpty().map { it as String }.toSet()
        val login =
            SystemLogin(body.getValue("username") as String, body["tenant"] as String?, body["app"] as String?, roles)
        val accessSeconds = (body["accessSeconds"] as Number?)?.toLong() ?: return authentication.authenticateAsToken(login)
        val subject = authentication.authenticate(login)
        val tenant = subject.tenant?.let(::TenantId) ?: TenantId.DEFAULT
        val session = subject.inSession(families.start(tenant, PrincipalId(subject.id)))
        return converter.toToken(session, Duration.ofSeconds(accessSeconds), Duration.ofDays(1))
    }
}
