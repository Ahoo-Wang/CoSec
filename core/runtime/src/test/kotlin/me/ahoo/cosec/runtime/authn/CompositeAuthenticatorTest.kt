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

package me.ahoo.cosec.runtime.authn

import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.authn.Credentials
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** A request with an API key is judged by the key alone; any other by the token authenticator. */
class CompositeAuthenticatorTest {
    private val rejected = AuthnResult.Rejected(AuthnResult.RejectReason.API_KEY_INVALID)
    private val token = AuthnResult.Rejected(AuthnResult.RejectReason.TOKEN_EXPIRED)
    private val seen = ArrayList<String>()
    private val tokenAuthenticator = Authenticator {
        seen += "token"
        token
    }
    private val apiKeyAuthenticator = Authenticator {
        seen += "apikey"
        rejected
    }

    @Test
    fun aKeyIsJudgedAlone() = runTest {
        CompositeAuthenticator(tokenAuthenticator, apiKeyAuthenticator).authenticate(Credentials("t", "k"))
            .assert().isSameAs(rejected)
        seen.assert().containsExactly("apikey")
    }

    @Test
    fun withoutAKeyTheTokenDecides() = runTest {
        CompositeAuthenticator(tokenAuthenticator, apiKeyAuthenticator).authenticate(Credentials("t", null))
            .assert().isSameAs(token)
        seen.assert().containsExactly("token")
    }

    @Test
    fun withoutAKeyAuthenticatorKeysAreNotRead() = runTest {
        CompositeAuthenticator(tokenAuthenticator).authenticate(Credentials("t", "k")).assert().isSameAs(token)
        seen.assert().containsExactly("token")
    }
}
