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

package me.ahoo.cosec.compat

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * MIX-2 and MIX-3, CoSec's tokens between versions: a token one version issues, the other verifies as the current
 * verifier does, and a refresh token is never taken for an access token by either. Both issuers declare `cosec.kind`
 * (`IDN-45`) and both verifiers read it (`IDN-46`); a `crit` header a verifier does not understand makes it refuse the
 * token (`IDN-47`).
 */
class TokenCompatTest {
    private val secret = "compat-secret-compat-secret-compat-secret"

    @Test
    fun `each version verifies what the other issues as the current verifier does`() {
        for (sample in listOf("person", "platform")) {
            for ((issuer, verifier) in PAIRS) {
                val access = issuer("token.issue", "secret" to secret, "sample" to sample).substringBefore(' ')
                val read = verifier("token.verify", "secret" to secret, "token" to access)
                read.assert().describedAs("$issuer -> $verifier, $sample").doesNotStartWith("refused:")
                read.assert().describedAs("$issuer -> $verifier, $sample")
                    .isEqualTo(Side.CURRENT("token.verify", "secret" to secret, "token" to access))
            }
        }
    }

    /** `IDN-45`, `IDN-46`: whose a token is, as one version's issuer declared it, the other version's verifier reads alike. */
    @Test
    fun `each version reads the kind the other's issuer declared`() {
        for (sample in listOf("person", "platform")) {
            for ((issuer, verifier) in PAIRS) {
                val access = issuer("token.issue", "secret" to secret, "sample" to sample).substringBefore(' ')
                verifier("token.kind", "secret" to secret, "token" to access).assert()
                    .describedAs("$issuer -> $verifier, $sample").isEqualTo("person")
            }
        }
    }

    /**
     * `IDN-46`: a token without `cosec.kind`, as an earlier preview issued, or with a value neither version knows, a
     * string or not, is a workload's to both verifiers: never a person's, never refused.
     */
    @Test
    fun `a token without a kind, or with one neither version knows, is a workload's to both verifiers`() {
        for (issuer in listOf(Side.CURRENT, Side.PREVIOUS)) {
            val access = issuer("token.issue", "secret" to secret, "sample" to "person").substringBefore(' ')
            for ((what, value) in listOf("no kind" to null, "an unknown kind" to "\"robot\"", "a number" to "1")) {
                val token = Jws.withKind(access, secret, value)
                for (verifier in listOf(Side.CURRENT, Side.PREVIOUS)) {
                    verifier("token.verify", "secret" to secret, "token" to token).assert()
                        .describedAs("$issuer -> $verifier, $what").doesNotStartWith("refused:")
                    verifier("token.kind", "secret" to secret, "token" to token).assert()
                        .describedAs("$issuer -> $verifier, $what").isEqualTo("workload")
                }
            }
        }
    }

    /**
     * `IDN-47`: a token whose signed header marks an extension critical is not read by a version that does not understand
     * it: each refuses it as a token it does not understand (`503`).
     */
    @Test
    fun `a token whose crit names an extension is refused by a version that does not understand it`() {
        for (issuer in listOf(Side.CURRENT, Side.PREVIOUS)) {
            val access = Jws.withCrit(
                issuer("token.issue", "secret" to secret, "sample" to "person").substringBefore(' '),
                secret
            )
            for (verifier in listOf(Side.CURRENT, Side.PREVIOUS)) {
                verifier("token.verify", "secret" to secret, "token" to access).assert()
                    .describedAs("$issuer -> $verifier").startsWith("refused:TokenNotUnderstoodException")
            }
        }
    }

    /** `IDN-47`, MIX-3: a refresh reads the refresh token whole too. */
    @Test
    fun `a refresh token whose crit names an extension is refused by a version that does not understand it`() {
        for (issuer in listOf(Side.CURRENT, Side.PREVIOUS)) {
            val (access, refresh) = issuer("token.issue", "secret" to secret, "sample" to "person").split(' ')
            val critical = Jws.withCrit(refresh, secret)
            for (version in listOf(Side.CURRENT, Side.PREVIOUS)) {
                // The pair as issued refreshes on both: what changes below is the refresh token's header alone.
                version("token.refresh", "secret" to secret, "access" to access, "refresh" to refresh).assert()
                    .describedAs("$issuer -> $version").doesNotStartWith("refused:")
            }
            for (version in listOf(Side.CURRENT, Side.PREVIOUS)) {
                version("token.refresh", "secret" to secret, "access" to access, "refresh" to critical).assert()
                    .describedAs("$issuer -> $version").startsWith("refused:TokenNotUnderstoodException")
            }
        }
    }

    @Test
    fun `a refresh token is refused as an access token by every version`() {
        for ((issuer, verifier) in PAIRS) {
            val refresh = issuer("token.issue", "secret" to secret, "sample" to "person").substringAfter(' ')
            verifier("token.verify", "secret" to secret, "token" to refresh).assert()
                .describedAs("$issuer -> $verifier").startsWith("refused:")
        }
    }
}
