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
import me.ahoo.cosec.login.token.RefreshTokenCredentials
import me.ahoo.cosec.runtime.http.PathNormalizer
import me.ahoo.cosec.spring.boot.authentication.ConditionalOnLoginEnabled
import me.ahoo.cosec.spring.boot.authentication.LoginProperties
import me.ahoo.cosec.spring.boot.jwt.ConditionalOnJwtEnabled
import me.ahoo.cosec.spring.boot.jwt.JwtProperties
import me.ahoo.cosec.spring.boot.pipeline.GATEWAY_GLOBAL_FILTER
import org.springframework.boot.web.context.reactive.ReactiveWebApplicationContext
import org.springframework.context.ApplicationContext
import org.springframework.core.ResolvableType
import org.springframework.util.ClassUtils
import org.springframework.web.context.WebApplicationContext

/**
 * `STA-1` for the served login routes: a path that is set never does nothing. Startup fails, naming the property, when
 * the host is no web application, is a Spring Cloud Gateway (a gateway verifies sessions and issues none: the service
 * that issues them serves the route, behind the gateway), the path is not one plain absolute path, both routes have one
 * path, or the route was not wired because login (`cosec.authentication.login.enabled`) or JWT (`cosec.jwt.enabled`)
 * is off. Run once every singleton exists, so it reads what was wired rather than guessing it.
 *
 * `STA-8` is the last of it: a refresh route the starter builds, with no `MembershipRefresh` bean, needs the statement
 * that its tokens keep the roles of the token they replace ([RefreshMembershipChoice]); an application's own
 * `RefreshTokenAuthentication` has chosen already.
 */
internal object LoginRoutesCheck {
    fun check(context: ApplicationContext) {
        val environment = context.environment
        val refresh = environment.getProperty(LoginProperties.REFRESH_PATH_KEY)
        val logout = environment.getProperty(LoginProperties.LOGOUT_PATH_KEY)
        val set = listOfNotNull(
            refresh?.let { LoginProperties.REFRESH_PATH_KEY to it },
            logout?.let { LoginProperties.LOGOUT_PATH_KEY to it },
        )
        if (set.isEmpty()) return
        val names = set.joinToString(" and ") { it.first }
        check(context is WebApplicationContext || context is ReactiveWebApplicationContext) {
            "$names is set, but this application serves no HTTP: there is no route to serve."
        }
        check(!ClassUtils.isPresent(GATEWAY_GLOBAL_FILTER, context.classLoader)) {
            "$names is set on a Spring Cloud Gateway host. A gateway verifies sessions and issues none: serve the " +
                "route from the service that issues them, behind the gateway, and unset it here."
        }
        set.forEach { (name, path) ->
            check(path.startsWith("/") && PathNormalizer.normalize(path) == path && path.none { it in RESERVED }) {
                "$name=$path is not one plain absolute path (no pattern, no dot segment, no encoded character)."
            }
        }
        check(refresh == null || refresh != logout) { "$names name one path: each route needs its own." }
        val wired = (refresh == null || context.getBeanProvider(RefreshRoute::class.java).getIfAvailable() != null) &&
            (logout == null || context.getBeanProvider(LogoutRoute::class.java).getIfAvailable() != null)
        check(wired) {
            "$names is set, but login or JWT is off (${ConditionalOnLoginEnabled.ENABLED_KEY}, " +
                "${ConditionalOnJwtEnabled.ENABLED_KEY}): the routes issue and end JWT sessions, turn both on."
        }
        if (refresh != null && context.containsBean(CoSecLoginRoutesAutoConfiguration.REFRESH_AUTHENTICATION_BEAN)) {
            val membership = context.getBeanProvider<MembershipRefresh<RefreshTokenCredentials>>(MEMBERSHIP)
            val hasBean = membership.getIfAvailable() != null
            RefreshMembershipChoice.require(environment, context.getBean(JwtProperties::class.java), hasBean)
        }
    }

    private val MEMBERSHIP = ResolvableType.forClassWithGenerics(
        MembershipRefresh::class.java,
        RefreshTokenCredentials::class.java
    )

    private val RESERVED = setOf('*', '{', '}', '?', '#', '%', ';')
}
