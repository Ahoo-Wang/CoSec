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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.runtime.stage

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.call.Answer
import me.ahoo.cosec.runtime.call.callPort
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.http.Outcome
import me.ahoo.cosec.runtime.http.QueryParameters
import me.ahoo.cosec.runtime.http.StatusMapping
import me.ahoo.cosec.runtime.http.Unavailability
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.authn.Credentials
import kotlin.time.Duration

/**
 * Stage 2: who the credentials prove. A failed, timed-out or stray-cancelled authenticator is a 503, never anonymous
 * (PIP-4); a rejected credential goes on as anonymous (RFC 0006 K2).
 */
internal class Authenticate(
    private val authenticator: Authenticator,
    private val timeout: Duration,
    /** `cosec.pipeline.api-key-header`. */
    private val apiKeyHeader: String,
    /** `cosec.pipeline.allow-query-token`. */
    private val allowQueryToken: Boolean,
    /** `cosec.pipeline.allow-cookie-token`. */
    private val allowCookieToken: Boolean,
) : Stage {
    override suspend fun run(state: RequestState): Outcome? {
        val credentials = credentials(state.facts)
        val authn = when (val answer = callPort(timeout) { authenticator.authenticate(credentials) }) {
            is Answer.Value -> answer.value
            is Answer.Failed -> AuthnResult.Unavailable(answer.error)
        }
        state.authn = authn
        if (authn is AuthnResult.Unavailable) {
            return StatusMapping.unavailable(
                state.requestId,
                Unavailability.AuthenticatorUnavailable(authn.cause),
                state.traceId,
            )
        }
        return null
    }

    /**
     * The token is the first non-blank one of the `Authorization` header, the query parameter when allowed, and the
     * cookie when allowed: the header is the channel a client sets on purpose, the others are fallbacks for clients that
     * cannot set it.
     */
    private fun credentials(facts: HttpFacts): Credentials {
        val token = facts.headers.values(AUTHORIZATION).firstOrNull { it.isNotBlank() }
            ?: queryToken(facts)
            ?: cookieToken(facts)
        val apiKey = facts.headers.values(apiKeyHeader).firstOrNull { it.isNotBlank() }
        return if (token == null && apiKey == null) NO_CREDENTIALS else Credentials(token, apiKey)
    }

    private fun queryToken(facts: HttpFacts): String? {
        if (!allowQueryToken) {
            return null
        }
        return QueryParameters.values(
            facts.rawQuery,
            AUTHORIZATION_NAMES
        )?.get(AUTHORIZATION)?.firstOrNull { it.isNotBlank() }
    }

    private fun cookieToken(facts: HttpFacts): String? =
        if (allowCookieToken) facts.cookies.values(AUTHORIZATION).firstOrNull { it.isNotBlank() } else null

    companion object {
        /** The header, query parameter and cookie a token is read from. */
        const val AUTHORIZATION = "authorization"
        private val AUTHORIZATION_NAMES = setOf(AUTHORIZATION)
        private val NO_CREDENTIALS = Credentials(null, null)
    }
}
