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
    data class Provider(
        var type: AuthDefaultSource,
        var clientId: String,
        var clientSecret: String? = null,
        var redirectUri: String? = null,
        var alipayPublicKey: String? = null,
        var unionId: Boolean = false,
        var stackOverflowKey: String? = null,
        var agentId: String? = null,
        var usertype: String? = null,
        var domainPrefix: String? = null,
        var httpConfig: HttpConfig? = null,
        var ignoreCheckState: Boolean = false,
        var scopes: MutableList<String>? = null,
        var deviceId: String? = null,
        var clientOsType: Int? = null,
        var packId: String? = null,
        var pkce: Boolean = false,
        var authServerId: String? = null,
        var ignoreCheckRedirectUri: Boolean = false,
        var tenantId: String? = null,
        var kid: String? = null,
        var teamId: String? = null,
        var loginType: String = "CorpApp",
        var lang: String = "zh",
        var dingTalkOrgType: String? = null,
        var dingTalkCorpId: String? = null,
        var dingTalkExclusiveLogin: Boolean = false,
        var dingTalkExclusiveCorpId: String? = null,
    ) {
        fun toJustAuthConfig(): AuthConfig {
            return AuthConfig(
                /* clientId = */
                clientId,
                /* clientSecret = */
                clientSecret,
                /* redirectUri = */
                redirectUri,
                /* alipayPublicKey = */
                alipayPublicKey,
                /* unionId = */
                unionId,
                /* stackOverflowKey = */
                stackOverflowKey,
                /* agentId = */
                agentId,
                /* usertype = */
                usertype,
                /* domainPrefix = */
                domainPrefix,
                /* httpConfig = */
                httpConfig,
                /* ignoreCheckState = */
                ignoreCheckState,
                /* scopes = */
                scopes,
                /* deviceId = */
                deviceId,
                /* clientOsType = */
                clientOsType,
                /* packId = */
                packId,
                /* pkce = */
                pkce,
                /* authServerId = */
                authServerId,
                /* ignoreCheckRedirectUri = */
                ignoreCheckRedirectUri,
                /* tenantId = */
                tenantId,
                /* kid = */
                kid,
                /* teamId = */
                teamId,
                /* loginType = */
                loginType,
                /* lang = */
                lang,
                /* dingTalkOrgType = */
                dingTalkOrgType,
                /* dingTalkCorpId = */
                dingTalkCorpId,
                /* dingTalkExclusiveLogin = */
                dingTalkExclusiveLogin,
                /* dingTalkExclusiveCorpId = */
                dingTalkExclusiveCorpId,
            )
        }
    }
}
