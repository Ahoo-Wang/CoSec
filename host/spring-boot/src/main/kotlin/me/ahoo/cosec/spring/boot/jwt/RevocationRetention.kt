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

package me.ahoo.cosec.spring.boot.jwt

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spring.boot.pipeline.RevocationProperties
import java.time.Duration

private val log = KotlinLogging.logger {}

/**
 * How long a token revocation is kept: past the end of every token it can revoke, so that no revoked token is valid
 * again when its record expires. A revocation names a token by its id, an access token's as well as a refresh token's,
 * and a token lives at most its validity past its issue, from an issuer whose clock runs at most the revocation skew
 * ahead (`IDN-29`): so the longer of the two validities, plus the skew. Computed, never configured: nothing to refuse at
 * startup, since an access validity longer than the refresh validity is covered too.
 */
internal object RevocationRetention {
    fun of(jwt: JwtProperties, revocation: RevocationProperties): Duration =
        maxOf(jwt.tokenValidity.access, jwt.tokenValidity.refresh) + revocation.skew

    /** [of], said once at startup with what it is derived from. */
    fun logged(jwt: JwtProperties, revocation: RevocationProperties): Duration =
        of(jwt, revocation).also { log.info { "A token revocation is kept for ${describe(jwt, revocation)}." } }

    /** What [of] is derived from, for the startup line and the messages that name it. */
    fun describe(jwt: JwtProperties, revocation: RevocationProperties): String =
        "${of(jwt, revocation)}: the longer of ${JwtProperties.ACCESS_VALIDITY_KEY} (${jwt.tokenValidity.access}) and " +
            "${JwtProperties.REFRESH_VALIDITY_KEY} (${jwt.tokenValidity.refresh}), plus " +
            "${RevocationProperties.PREFIX}.skew (${revocation.skew})"
}
