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

package me.ahoo.cosec.jwt

import com.auth0.jwt.exceptions.JWTDecodeException
import com.auth0.jwt.interfaces.DecodedJWT
import me.ahoo.cosec.spi.token.TokenNotUnderstoodException
import me.ahoo.cosec.spi.token.TokenVerificationException

/**
 * The JWS `crit` header parameter (RFC 7515 §4.1.11, `IDN-47`): the extensions a later issuer requires a reader to
 * understand, which `java-jwt` does not process. This version understands none, so a token whose protected header names
 * any is refused, never read without it; a token without `crit` reads as before. Checked once a token's signature is
 * verified, so only a token the issuer signed can be refused as not understood: anything else stays an invalid token.
 */
internal object CriticalHeader {
    const val NAME = "crit"

    /**
     * The header parameters RFC 7515 and RFC 7516 define (and JWA's for them), which `crit` must never name: a reader
     * understands them already.
     */
    private val REGISTERED = setOf(
        "alg", "jku", "jwk", "kid", "x5u", "x5c", "x5t", "x5t#S256", "typ", "cty", "crit",
        "enc", "zip", "epk", "apu", "apv", "iv", "tag", "p2s", "p2c",
    )

    /**
     * Passes a token whose header has no `crit`. Throws [TokenVerificationException] for a malformed one (not a non-empty
     * array of distinct names, a name the header does not hold, or a registered one), and [TokenNotUnderstoodException]
     * for a well-formed one: every name it lists is one this version does not understand.
     */
    fun requireUnderstood(decoded: DecodedJWT) {
        val crit = decoded.getHeaderClaim(NAME)
        if (crit.isMissing) return
        val names = namesOf(crit)
        problemOf(decoded, names)?.let { throw malformed(it) }
        throw TokenNotUnderstoodException(
            "The token's crit header names ${names.joinToString(", ", "[", "]")}, which this version of CoSec does not " +
                "understand: a later version issued it, and it passes once this instance is upgraded."
        )
    }

    /** What is wrong with [names] as the `crit` of [decoded]: a registered parameter, or one the header does not hold. */
    private fun problemOf(decoded: DecodedJWT, names: List<String>): String? {
        names.firstOrNull { it in REGISTERED }?.let { return "names the registered header parameter [$it]" }
        val absent = names.firstOrNull { decoded.getHeaderClaim(it).isMissing }
        absent?.let { return "names [$it], which the header does not hold" }
        return null
    }

    private fun namesOf(crit: com.auth0.jwt.interfaces.Claim): List<String> {
        val values = if (crit.isNull) {
            null
        } else {
            try {
                crit.asList(Any::class.java)
            } catch (_: JWTDecodeException) {
                null
            }
        }
        if (values.isNullOrEmpty() || values.any { it !is String || it.isEmpty() }) {
            throw malformed("is not a non-empty array of names")
        }
        @Suppress("UNCHECKED_CAST")
        val names = values as List<String>
        if (names.toSet().size != names.size) throw malformed("repeats a name")
        return names
    }

    private fun malformed(what: String) = TokenVerificationException("The token's crit header $what.")
}
