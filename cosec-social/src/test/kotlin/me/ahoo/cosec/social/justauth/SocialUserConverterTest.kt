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
import me.ahoo.cosec.social.SocialUser
import me.ahoo.cosec.social.justauth.SocialUserConverter.toSocialUserGender
import me.ahoo.cosec.social.justauth.SocialUserConverter.toSocialUser
import me.ahoo.test.asserts.assert
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

}