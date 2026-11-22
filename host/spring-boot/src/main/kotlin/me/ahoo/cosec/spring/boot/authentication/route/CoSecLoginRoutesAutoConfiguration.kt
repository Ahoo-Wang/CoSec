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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.spring.boot.authentication.route

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.login.route.LogoutRoute
import me.ahoo.cosec.login.route.RefreshRoute
import me.ahoo.cosec.login.token.MembershipRefresh
import me.ahoo.cosec.login.token.RefreshTokenAuthentication
import me.ahoo.cosec.login.token.RefreshTokenCredentials
import me.ahoo.cosec.login.token.TokenConverter
import me.ahoo.cosec.login.token.TokenFamilies
import me.ahoo.cosec.runtime.http.ProblemBodies
import me.ahoo.cosec.runtime.token.TokenRevocation
import me.ahoo.cosec.spi.token.TokenRevoker
import me.ahoo.cosec.spi.token.TokenVerifier
import me.ahoo.cosec.spring.boot.ConditionalOnCoSecEnabled
import me.ahoo.cosec.spring.boot.authentication.ConditionalOnLoginEnabled
import me.ahoo.cosec.spring.boot.authentication.LoginProperties
import me.ahoo.cosec.spring.boot.jwt.CoSecJwtAutoConfiguration
import me.ahoo.cosec.spring.boot.jwt.ConditionalOnJwtEnabled
import me.ahoo.cosec.spring.boot.pipeline.ApplicationPort
import me.ahoo.cosec.spring.boot.pipeline.GATEWAY_GLOBAL_FILTER
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.web.reactive.function.server.RouterFunction as ReactiveRouterFunction
import org.springframework.web.reactive.function.server.ServerResponse as ReactiveServerResponse
import org.springframework.web.servlet.function.RouterFunction as ServletRouterFunction
import org.springframework.web.servlet.function.ServerResponse as ServletServerResponse

/**
 * The refresh and logout routes the starter serves when `cosec.authentication.login.refresh.path` or `logout.path` is
 * set (`IDN-23`, `IDN-24`), on WebFlux and on Spring MVC, after the control API's shape: a stack-neutral route of
 * `cosec-login` behind a router function of each stack. They pass CoSec like every route (`STA-4`): nothing is
 * exempted from the filter, and a document must permit an anonymous `POST` of each path. Never on a gateway: a gateway
 * verifies sessions and issues none, and `CoSecModuleCheckAutoConfiguration` fails its startup when a path is set.
 *
 * The refresh route refreshes with the application's [RefreshTokenAuthentication] bean when it declares one, its hook
 * included, else with the starter's, built over the application's `MembershipRefresh<RefreshTokenCredentials>` bean
 * when there is one (`IDN-25`). The route presents [RefreshTokenCredentials]: an application refresh authentication
 * over a credentials class of its own cannot serve it, and fails startup rather than be left out of the route.
 *
 * With no `MembershipRefresh` bean the starter's refresh keeps the memberships of the token it replaces, and only when the
 * operator states it (`cosec.jwt.session.memberships-on-refresh=from-token`): otherwise startup fails (`STA-8`).
 */
@AutoConfiguration(after = [CoSecJwtAutoConfiguration::class])
@ConditionalOnCoSecEnabled
@ConditionalOnLoginEnabled
@ConditionalOnJwtEnabled
@ConditionalOnLoginRoute
@EnableConfigurationProperties(LoginProperties::class)
internal class CoSecLoginRoutesAutoConfiguration {
    /** The refresh the route serves when the application declares none of its own. */
    @Bean(REFRESH_AUTHENTICATION_BEAN)
    @ConditionalOnProperty(LoginProperties.REFRESH_PATH_KEY)
    @ConditionalOnMissingBean(RefreshTokenAuthentication::class)
    fun cosecRefreshTokenAuthentication(
        environment: Environment,
        verifier: TokenVerifier,
        revocation: TokenRevocation,
        families: TokenFamilies,
        membership: ObjectProvider<MembershipRefresh<RefreshTokenCredentials>>,
    ): RefreshTokenAuthentication<RefreshTokenCredentials> = RefreshTokenAuthentication(
        verifier,
        revocation,
        families,
        RefreshMembershipChoice.membershipOf(environment, membership.getIfAvailable())
    )

    @Bean
    @ConditionalOnProperty(LoginProperties.REFRESH_PATH_KEY)
    fun cosecRefreshRoute(
        refresh: RefreshTokenAuthentication<*>,
        converter: TokenConverter,
        bodies: ObjectProvider<ProblemBodies>,
    ): RefreshRoute {
        check(refresh.credentialsType == RefreshTokenCredentials::class.java) {
            "${LoginProperties.REFRESH_PATH_KEY} is set, but the RefreshTokenAuthentication bean refreshes " +
                "${refresh.credentialsType.name}, not the pair the served route presents: serve that refresh from a " +
                "route of your own and unset ${LoginProperties.REFRESH_PATH_KEY}, or declare it over " +
                "RefreshTokenCredentials (IDN-25)."
        }
        @Suppress("UNCHECKED_CAST")
        return RefreshRoute(
            refresh as RefreshTokenAuthentication<RefreshTokenCredentials>,
            converter,
            bodies.getIfAvailable { ProblemBodies.DEFAULT },
        )
    }

    @Bean
    @ConditionalOnProperty(LoginProperties.LOGOUT_PATH_KEY)
    fun cosecLogoutRoute(revoker: TokenRevoker, bodies: ObjectProvider<ProblemBodies>): LogoutRoute =
        LogoutRoute(revoker, bodies.getIfAvailable { ProblemBodies.DEFAULT })

    @Bean
    fun cosecLoginRouteDenials(properties: LoginProperties): LoginRouteDenials = LoginRouteDenials(routesOf(properties))

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    @ConditionalOnClass(name = ["me.ahoo.cosec.webflux.ServerWebExchanges"])
    @ConditionalOnMissingClass(GATEWAY_GLOBAL_FILTER)
    class WebFlux {
        /** Where the application's server bound (`CTL-69`); the control routes' configuration declares it too. */
        @Bean
        @ConditionalOnMissingBean
        fun cosecApplicationPort(): ApplicationPort = ApplicationPort()

        @Bean
        fun cosecLoginRoutes(
            properties: LoginProperties,
            refresh: ObjectProvider<RefreshRoute>,
            logout: ObjectProvider<LogoutRoute>,
            applicationPort: ApplicationPort,
            bodies: ObjectProvider<ProblemBodies>,
        ): ReactiveRouterFunction<ReactiveServerResponse> = ReactiveLoginRoutes.of(
            handlersOf(properties, refresh, logout),
            applicationPort.predicate(),
            bodies.getIfAvailable { ProblemBodies.DEFAULT },
        )
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = ["me.ahoo.cosec.webmvc.ServletRequests"])
    class WebMvc {
        @Bean
        fun cosecLoginRoutes(
            properties: LoginProperties,
            refresh: ObjectProvider<RefreshRoute>,
            logout: ObjectProvider<LogoutRoute>,
            bodies: ObjectProvider<ProblemBodies>,
        ): ServletRouterFunction<ServletServerResponse> =
            ServletLoginRoutes.of(
                handlersOf(properties, refresh, logout),
                bodies.getIfAvailable { ProblemBodies.DEFAULT }
            )
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = ["org.springframework.boot.health.contributor.HealthIndicator"])
    class Health {
        @Bean
        @ConditionalOnBean(LoginRouteDenials::class)
        fun cosecLoginRoutesHealthIndicator(
            properties: LoginProperties,
            denials: LoginRouteDenials,
        ): LoginRoutesHealthIndicator = LoginRoutesHealthIndicator(routesOf(properties), denials)
    }

    internal companion object {
        /** The starter's own refresh, which [LoginRoutesCheck] holds to a choice of memberships (`STA-8`). */
        const val REFRESH_AUTHENTICATION_BEAN = "cosecRefreshTokenAuthentication"

        /** The property of each served route, by its path. */
        fun routesOf(properties: LoginProperties): Map<String, String> = buildMap {
            properties.refresh.path?.let { put(it, LoginProperties.REFRESH_PATH_KEY) }
            properties.logout.path?.let { put(it, LoginProperties.LOGOUT_PATH_KEY) }
        }

        fun handlersOf(
            properties: LoginProperties,
            refresh: ObjectProvider<RefreshRoute>,
            logout: ObjectProvider<LogoutRoute>,
        ): Map<String, LoginRouteHandler> = buildMap {
            properties.refresh.path?.let { put(it, LoginRouteHandler.of(refresh.getObject())) }
            properties.logout.path?.let { put(it, LoginRouteHandler.of(logout.getObject())) }
        }
    }
}
