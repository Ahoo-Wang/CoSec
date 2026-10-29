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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import reactor.kotlin.test.test

internal class DirectSocialUserPrincipalConverterTest {

    @Test
    fun convert() {
        val authUser = SocialUser(id = "id", username = "username", provider = "provider")
        DirectSocialUserPrincipalConverter.convert("clientId", authUser)
            .test()
            .consumeNextWith {
                it.id.assert().isEqualTo("id@clientId")
            }
            .verifyComplete()
    }

    /**
     * RC1-25: only the provider registration and the provider's user id become (signed) attributes; profile fields the
     * user edits, the raw user info and names CoSec sets for other credentials never do, and the input is left alone.
     */
    @Test
    fun onlyProviderOwnedFieldsBecomeAttributes() {
        val rawInfo = mutableMapOf<String, Any>(
            "login" to "octocat",
            "company" to "Acme",
            "bio" to "admin",
            "apiKeyId" to "k1",
            SocialUserPrincipalConverter.OAUTH_USER_ID to "forged",
        )
        val authUser = SocialUser(
            id = "583231",
            username = "octocat",
            nickname = "The Octocat",
            email = "octocat@example.com",
            rawInfo = rawInfo,
            provider = "github",
        )
        DirectSocialUserPrincipalConverter.convert("github", authUser)
            .test()
            .consumeNextWith {
                it.attributes.assert().isEqualTo(
                    mapOf(
                        SocialUserPrincipalConverter.OAUTH_PROVIDER to "github",
                        SocialUserPrincipalConverter.OAUTH_USER_ID to "583231",
                    )
                )
                it.roles.assert().isEmpty()
                it.policies.assert().isEmpty()
            }
            .verifyComplete()
        rawInfo.keys.assert().doesNotContain(SocialUserPrincipalConverter.OAUTH_PROVIDER)
    }
}
