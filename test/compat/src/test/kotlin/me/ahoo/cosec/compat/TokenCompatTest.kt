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
 * verifier does, and a refresh token is never taken for an access token by either.
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

    @Test
    fun `a refresh token is refused as an access token by every version`() {
        for ((issuer, verifier) in PAIRS) {
            val refresh = issuer("token.issue", "secret" to secret, "sample" to "person").substringAfter(' ')
            verifier("token.verify", "secret" to secret, "token" to refresh).assert()
                .describedAs("$issuer -> $verifier").startsWith("refused:")
        }
    }
}
