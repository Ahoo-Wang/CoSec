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
 * verifier does, and a refresh token is never taken for an access token by either. The current issuer's `cosec.kind`
 * is a claim beta.8's verifier ignores (`IDN-45`); a beta.8 token, which has none, is a workload's to the current
 * verifier (`IDN-46`). A `crit` header the current verifier does not understand makes it refuse the token, where beta.8
 * reads the token without it (`IDN-47`).
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

    /**
     * `IDN-46`: the current verifier reads whose a token is from `cosec.kind`, and a token that does not say, as every
     * token a previous release issued, as a workload's, never refusing it: such a session approves nothing until its
     * user signs in again.
     */
    @Test
    fun `the current verifier reads the kind the current issuer declared, and a token without one as a workload's`() {
        for (sample in listOf("person", "platform")) {
            val current = Side.CURRENT("token.issue", "secret" to secret, "sample" to sample).substringBefore(' ')
            Side.CURRENT("token.kind", "secret" to secret, "token" to current).assert()
                .describedAs("current -> current, $sample").isEqualTo("person")
            val previous = Side.PREVIOUS("token.issue", "secret" to secret, "sample" to sample).substringBefore(' ')
            Side.CURRENT("token.kind", "secret" to secret, "token" to previous).assert()
                .describedAs("previous -> current, $sample").isEqualTo("workload")
        }
    }

    /**
     * `IDN-47`: a token whose signed header marks an extension critical is not read by a version that does not understand
     * it. The current verifier refuses it as a token it does not understand (`503`); beta.8's ignores `crit`, a gap that
     * closes from 8.0.0-beta.9.
     */
    @Test
    fun `a token whose crit names an extension is refused by a version that does not understand it`() {
        for (issuer in listOf(Side.CURRENT, Side.PREVIOUS)) {
            val access = Jws.withCrit(
                issuer("token.issue", "secret" to secret, "sample" to "person").substringBefore(' '),
                secret
            )
            Side.CURRENT("token.verify", "secret" to secret, "token" to access).assert()
                .describedAs("$issuer -> current").startsWith("refused:TokenNotUnderstoodException")
            Baseline.from("8.0.0-beta.9", "the previous verifier refuses a token whose crit it does not understand") {
                Side.PREVIOUS("token.verify", "secret" to secret, "token" to access).assert()
                    .describedAs("$issuer -> previous").startsWith("refused:")
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
            Side.CURRENT("token.refresh", "secret" to secret, "access" to access, "refresh" to critical).assert()
                .describedAs("$issuer -> current").startsWith("refused:TokenNotUnderstoodException")
            Baseline.from(
                "8.0.0-beta.9",
                "the previous verifier refuses a refresh token whose crit it does not understand"
            ) {
                Side.PREVIOUS("token.refresh", "secret" to secret, "access" to access, "refresh" to critical).assert()
                    .describedAs("$issuer -> previous").startsWith("refused:")
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
