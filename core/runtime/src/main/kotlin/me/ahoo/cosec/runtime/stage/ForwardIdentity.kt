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

package me.ahoo.cosec.runtime.stage

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.http.Outcome
import me.ahoo.cosec.runtime.http.StatusMapping
import me.ahoo.cosec.runtime.identity.IdentityIssuer
import me.ahoo.cosec.spi.identity.IdentityFormatException
import me.ahoo.cosec.spi.identity.IdentityOverflowException

/**
 * After the deadline, at a gateway that forwards the verified identity (RFC 0009 §5): what the outcome tells the
 * service behind, always naming the header, so whatever the client sent is never forwarded. The identity on a permit
 * with a principal, only to a route the gateway marks as receiving it ([IdentityIssuer.FORWARD_ATTRIBUTE] `true`,
 * RC1-49); otherwise none, so a request no gateway route marks forwards nothing. An identity too large to forward, or
 * one the codec refuses to write because no service would read it back (#1690), fails the request closed (500): no
 * header is forwarded.
 */
internal class ForwardIdentity(private val issuer: IdentityIssuer) {
    fun run(state: RequestState, outcome: Outcome): Outcome {
        val security = outcome.security
        val routed = state.input.requestAttributes[IdentityIssuer.FORWARD_ATTRIBUTE] == "true"
        if (!outcome.proceed || security == null || !routed) {
            return StatusMapping.forwarding(outcome, null)
        }
        return try {
            StatusMapping.forwarding(outcome, issuer.issue(security))
        } catch (e: IdentityOverflowException) {
            unforwardable(state, outcome, e)
        } catch (e: IdentityFormatException) {
            unforwardable(state, outcome, e)
        }
    }

    private fun unforwardable(state: RequestState, outcome: Outcome, e: RuntimeException): Outcome {
        requestPathLog.error(e) { "Request [${outcome.requestId}]: the identity cannot be forwarded." }
        return StatusMapping.error(outcome.requestId, e, state.traceId)
    }
}
