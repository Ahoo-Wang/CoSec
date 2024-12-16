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
package me.ahoo.cosec.spring.boot.starter.authentication.social

import com.xkcoding.http.config.HttpConfig
import me.ahoo.cosec.spring.boot.starter.EnabledCapable
import me.ahoo.cosec.spring.boot.starter.authentication.AuthenticationProperties
import me.zhyd.oauth.config.AuthConfig
import me.zhyd.oauth.config.AuthDefaultSource
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

/**
 * Social Authentication Properties .
 *
 * @author ahoo wang
 */
@ConfigurationProperties(prefix = SocialAuthenticationProperties.PREFIX)
class SocialAuthenticationProperties(
    @DefaultValue("true") override var enabled: Boolean = true,
    val registration: Map<String, Provider> = emptyMap()
) : EnabledCapable {
    companion object {
        const val PREFIX = AuthenticationProperties.PREFIX + ".social"
    }

    @Suppress("LongParameterList")
    class Provider(
        val type: AuthDefaultSource,
        clientId: String,
        clientSecret: String?,
        redirectUri: String?,
        alipayPublicKey: String?,
        unionId: Boolean = false,
        stackOverflowKey: String?,
        agentId: String?,
        usertype: String?,
        domainPrefix: String?,
        httpConfig: HttpConfig?,
        ignoreCheckState: Boolean = false,
        scopes: MutableList<String>?,
        deviceId: String?,
        clientOsType: Int?,
        packId: String?,
        pkce: Boolean = false,
        authServerId: String?,
        ignoreCheckRedirectUri: Boolean = false,
        tenantId: String?,
        kid: String?,
        teamId: String?,
        loginType: String = "CorpApp",
        lang: String = "zh",
        dingTalkOrgType: String?,
        dingTalkCorpId: String?,
        dingTalkExclusiveLogin: Boolean = false,
        dingTalkExclusiveCorpId: String?,
    ) : AuthConfig(
        clientId,
        clientSecret,
        redirectUri,
        alipayPublicKey,
        unionId,
        stackOverflowKey,
        agentId,
        usertype,
        domainPrefix,
        httpConfig,
        ignoreCheckState,
        scopes,
        deviceId,
        clientOsType,
        packId,
        pkce,
        authServerId,
        ignoreCheckRedirectUri,
        tenantId,
        kid,
        teamId,
        loginType,
        lang,
        dingTalkOrgType,
        dingTalkCorpId,
        dingTalkExclusiveLogin,
        dingTalkExclusiveCorpId,
    )
}
