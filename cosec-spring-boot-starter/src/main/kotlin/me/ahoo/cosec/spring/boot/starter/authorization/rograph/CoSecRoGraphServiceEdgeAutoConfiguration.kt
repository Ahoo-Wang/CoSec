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

package me.ahoo.cosec.spring.boot.starter.authorization.rograph

import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.request.RemoteIpResolver
import me.ahoo.cosec.context.request.RequestAttributesAppender
import me.ahoo.cosec.spring.boot.starter.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.starter.ENABLED_SUFFIX_KEY
import me.ahoo.cosec.spring.boot.starter.authorization.AuthorizationProperties
import me.ahoo.cosec.spring.boot.starter.authorization.CoSecAuthorizationAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.authorization.ConditionalOnAuthorizationEnabled
import me.ahoo.cosec.webflux.ReactiveXForwardedRemoteIpResolver
import me.ahoo.cosec.webflux.rograph.RoGraphServiceEdgeWebFilterFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.web.server.ServerWebExchange

@AutoConfiguration(after = [CoSecAuthorizationAutoConfiguration::class])
@ConditionalOnCoSecEnabled
@ConditionalOnAuthorizationEnabled
@ConditionalOnClass(name = ["me.ahoo.cosec.webflux.rograph.RoGraphServiceEdgeWebFilterFactory"])
@ConditionalOnProperty(
    value = [CoSecRoGraphServiceEdgeAutoConfiguration.ENABLED_KEY],
    matchIfMissing = false,
    havingValue = "true",
)
class CoSecRoGraphServiceEdgeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun roGraphServiceEdgeWebFilterFactory(
        securityContextParser: SecurityContextParser,
        authorization: Authorization,
        requestAttributesAppenderObjectProvider: ObjectProvider<RequestAttributesAppender>,
        remoteIpResolverObjectProvider: ObjectProvider<RemoteIpResolver<ServerWebExchange>>
    ): RoGraphServiceEdgeWebFilterFactory {
        return RoGraphServiceEdgeWebFilterFactory(
            securityContextParser = securityContextParser,
            authorization = authorization,
            requestAttributesAppenders = requestAttributesAppenderObjectProvider.toList(),
            remoteIpResolver = remoteIpResolverObjectProvider.getIfAvailable {
                ReactiveXForwardedRemoteIpResolver.TRUST_ALL
            },
        )
    }

    companion object {
        const val PREFIX = AuthorizationProperties.PREFIX + ".rograph.service-edge"
        const val ENABLED_KEY = PREFIX + ENABLED_SUFFIX_KEY
    }
}
