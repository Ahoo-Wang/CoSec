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

package me.ahoo.cosec.system

import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Access tokens the gateways accept, HS256 over the workflow's throwaway secret, signed here as `aot-smoke.sh` signs
 * them: until the system's own login service runs (batch 2), a scenario needs a token without one. Only the claims of
 * `contract/token/claims.json` that a token without a session carries.
 */
class Token(val value: String, val jti: String, val issuedAt: Instant, val expiresAt: Instant) {
    val bearer: Pair<String, String> get() = "Authorization" to "Bearer $value"

    companion object {
        fun issue(
            subject: String,
            tenant: String,
            roles: List<String> = emptyList(),
            app: String? = null,
            issuedAt: Instant = Instant.now(),
            validity: Duration = Duration.ofMinutes(10),
            secret: String = SystemTopology.jwtSecret,
        ): Token {
            val jti = UUID.randomUUID().toString().replace("-", "")
            val iat = issuedAt.epochSecond
            val exp = iat + validity.seconds
            val claims = linkedMapOf<String, Any>(
                "jti" to jti,
                "sub" to subject,
                "tenantId" to tenant,
                "iat" to iat,
                "exp" to exp
            )
            if (roles.isNotEmpty()) {
                claims["roles"] = roles
            }
            if (app != null) {
                claims["aud"] = app
            }
            val signing = part(mapOf("alg" to "HS256", "typ" to "JWT")) + "." + part(claims)
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
            val signature = ENCODER.encodeToString(mac.doFinal(signing.toByteArray()))
            return Token("$signing.$signature", jti, Instant.ofEpochSecond(iat), Instant.ofEpochSecond(exp))
        }

        private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

        private fun part(value: Any): String = ENCODER.encodeToString(Json.mapper.writeValueAsBytes(value))
    }
}
