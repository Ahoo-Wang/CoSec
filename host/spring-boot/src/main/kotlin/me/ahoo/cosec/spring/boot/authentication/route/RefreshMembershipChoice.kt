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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.login.token.MembershipRefresh
import me.ahoo.cosec.login.token.RefreshTokenCredentials
import me.ahoo.cosec.spring.boot.authentication.LoginProperties
import me.ahoo.cosec.spring.boot.jwt.JwtProperties
import org.springframework.core.env.Environment
import java.time.Duration

private val log = KotlinLogging.logger {}

/**
 * `STA-8`: how a token the starter's refresh route issues gets its memberships (its roles and attached policies) is a choice the operator writes, never a
 * default. With the route served and no `MembershipRefresh` bean, a refresh copies the memberships of the token it replaces,
 * so a role or an attached policy removed from a member keeps working until their next sign-in, `cosec.jwt.session.lifetime` at the latest
 * (30 days) while they refresh within the refresh token's lifetime. Where roles are read from the token only, this
 * decides how long a removal takes (`IDN-27`'s rule, for the starter's own wiring).
 *
 * The starter's refresh is built with [membershipOf]; [LoginRoutesCheck] then calls [require] once every singleton
 * exists, so a host that would serve the choice nobody made never starts.
 */
internal object RefreshMembershipChoice {
    /** What a refresh answers where nobody chose: never used, since [require] fails startup first. */
    private val UNSTATED: MembershipRefresh<RefreshTokenCredentials> = MembershipRefresh { _, _ ->
        error("${JwtProperties.MEMBERSHIPS_ON_REFRESH_KEY} states no choice (STA-8)")
    }

    /** The membership the starter's refresh uses: the application's bean, else [MembershipRefresh.CLAIMS] if stated. */
    fun membershipOf(
        environment: Environment,
        bean: MembershipRefresh<RefreshTokenCredentials>?,
    ): MembershipRefresh<RefreshTokenCredentials> = bean ?: if (stated(environment)) MembershipRefresh.CLAIMS else UNSTATED

    /** Fails with what to choose unless there is a bean or the statement; logs one INFO line for the statement. */
    fun require(environment: Environment, jwt: JwtProperties, hasBean: Boolean) {
        if (hasBean) return
        check(stated(environment)) { message(environment, jwt) }
        log.info {
            "${JwtProperties.MEMBERSHIPS_ON_REFRESH_KEY}=${JwtProperties.FROM_TOKEN}: a token the refresh route issues keeps " +
                "the memberships of the token it replaces (its roles and attached policies), so a role or an attached policy removed from a member applies at their next sign-in, " +
                "${JwtProperties.SESSION_LIFETIME_KEY}=${compact(jwt.session.lifetime)} at the latest (STA-8)."
        }
    }

    /** Exactly `from-token`, in any letter case; nothing else, an empty value and `true` included. */
    private fun stated(environment: Environment): Boolean {
        val written = environment.getProperty(JwtProperties.MEMBERSHIPS_ON_REFRESH_KEY)
        return written.equals(JwtProperties.FROM_TOKEN, ignoreCase = true)
    }

    private fun message(environment: Environment, jwt: JwtProperties): String {
        val written = environment.getProperty(JwtProperties.MEMBERSHIPS_ON_REFRESH_KEY)
        val lifetime = "${JwtProperties.SESSION_LIFETIME_KEY}=${compact(jwt.session.lifetime)}"
        val refresh = "${JwtProperties.REFRESH_VALIDITY_KEY}=${compact(jwt.tokenValidity.refresh)}"
        val access = "${JwtProperties.ACCESS_VALIDITY_KEY}=${compact(jwt.tokenValidity.access)}"
        val path = environment.getProperty(LoginProperties.REFRESH_PATH_KEY)
        return "${LoginProperties.REFRESH_PATH_KEY}=$path serves the refresh route, and nothing says how a refreshed token gets " +
            "its memberships. Without a choice it would copy the memberships of the token it replaces, its roles and attached policies: a role or an attached policy removed from a " +
            "member keeps working until that member signs in again, up to $lifetime as long as they refresh within " +
            "$refresh. Choose one: declare a MembershipRefresh<RefreshTokenCredentials> bean that reads the member's " +
            "roles and attached policies again at each refresh, so a removal takes effect within the access token's lifetime ($access); or " +
            "state that refreshed tokens keep the memberships of the token they replace, and accept the delay above. With " +
            "either, TokenRevoker.revokeSubject ends a member's tokens at once. To keep the token's memberships add " +
            "${JwtProperties.MEMBERSHIPS_ON_REFRESH_KEY}=${JwtProperties.FROM_TOKEN} (the environment variable " +
            "COSEC_JWT_SESSION_MEMBERSHIPSONREFRESH=${JwtProperties.FROM_TOKEN}). " +
            (if (written == null) "It is not set" else "It is [$written]") +
            " (STA-8, https://cosec.ahoo.me/integration/authentication#how-long-a-removed-role-lives)."
    }

    /** A duration as an operator writes it: `30d`, `12h`, `10m`, `30s`; anything else as ISO-8601. */
    private fun compact(duration: Duration): String = when {
        duration.isZero || duration.isNegative -> duration.toString()
        duration.toSeconds() % SECONDS_PER_DAY == 0L -> "${duration.toDays()}d"
        duration.toSeconds() % SECONDS_PER_HOUR == 0L -> "${duration.toHours()}h"
        duration.toSeconds() % SECONDS_PER_MINUTE == 0L -> "${duration.toMinutes()}m"
        duration.nano == 0 -> "${duration.toSeconds()}s"
        else -> duration.toString()
    }

    private const val SECONDS_PER_DAY = 86_400L
    private const val SECONDS_PER_HOUR = 3_600L
    private const val SECONDS_PER_MINUTE = 60L
}
