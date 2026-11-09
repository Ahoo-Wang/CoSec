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

package me.ahoo.cosec.social.justauth

import com.alibaba.fastjson.JSONObject
import me.ahoo.cosec.social.SocialAuthenticationException
import me.ahoo.cosec.social.SocialUser
import me.ahoo.cosec.social.justauth.SocialUserConverter.toSocialUser
import me.ahoo.cosec.social.justauth.SocialUserConverter.toSocialUserGender
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.zhyd.oauth.enums.AuthUserGender
import me.zhyd.oauth.model.AuthToken
import me.zhyd.oauth.model.AuthUser
import org.junit.jupiter.api.Test

class SocialUserConverterTest {
    @Test
    fun toSocialUserGender() {
        AuthUserGender.MALE.toSocialUserGender().assert().isEqualTo(SocialUser.Gender.MALE)
        AuthUserGender.FEMALE.toSocialUserGender().assert().isEqualTo(SocialUser.Gender.FEMALE)
        AuthUserGender.UNKNOWN.toSocialUserGender().assert().isEqualTo(SocialUser.Gender.UNKNOWN)
    }

    @Test
    fun toSocialUser() {
        val authUser = AuthUser()
        authUser.uuid = "uuid"
        authUser.username = "username"
        authUser.gender = AuthUserGender.MALE
        authUser.token = AuthToken()
        authUser.rawUserInfo = JSONObject()
        val coSecUser = authUser.toSocialUser("provider")
        coSecUser.id.assert().isEqualTo(authUser.uuid)
        coSecUser.username.assert().isEqualTo(authUser.username)
        coSecUser.gender.name.assert().isEqualTo(authUser.gender.name)
        coSecUser.rawInfo.assert().isEqualTo(authUser.rawUserInfo)
    }

    /** JustAuth's Figma provider builds its user without a gender (#1249): the login still succeeds. */
    @Test
    fun `a user without a gender reads as UNKNOWN`() {
        val authUser = AuthUser.builder().uuid("u1").username("demo").rawUserInfo(JSONObject()).build()
        authUser.gender.assert().isNull()
        val socialUser = authUser.toSocialUser("figma")
        socialUser.id.assert().isEqualTo("u1")
        socialUser.username.assert().isEqualTo("demo")
        socialUser.gender.assert().isEqualTo(SocialUser.Gender.UNKNOWN)
    }

    /** JustAuth's StackOverflow provider sets a nickname but no username (#1249): the provider's id stands in. */
    @Test
    fun `a user without a username is named by the provider's id`() {
        val authUser = AuthUser.builder().uuid("u2").nickname("Demo").gender(AuthUserGender.UNKNOWN)
            .rawUserInfo(JSONObject()).build()
        authUser.username.assert().isNull()
        val socialUser = authUser.toSocialUser("stackoverflow")
        socialUser.id.assert().isEqualTo("u2")
        socialUser.username.assert().isEqualTo("u2")
        socialUser.nickname.assert().isEqualTo("Demo")
    }

    @Test
    fun `a user without raw info has an empty one`() {
        val socialUser = AuthUser.builder().uuid("u3").username("demo").gender(AuthUserGender.FEMALE).build()
            .toSocialUser("provider")
        socialUser.rawInfo.assert().isEmpty()
        socialUser.gender.assert().isEqualTo(SocialUser.Gender.FEMALE)
    }

    /** The identity is the provider's id; a profile field never stands in for it. */
    @Test
    fun `a user without an id is refused`() {
        assertThrownBy<SocialAuthenticationException> {
            AuthUser.builder().username("demo").nickname("Demo").build().toSocialUser("provider")
        }
    }
}
