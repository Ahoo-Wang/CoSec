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

package me.ahoo.cosec.control.http

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.control.sdk.ControlService
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

/**
 * [ControlService] over the network (`CTL-77`): each call is the control API's route on a control-plane host's service
 * port, the same request the in-process implementation hands its control plane, and answers the same: a route's answer,
 * or its problem as a [me.ahoo.cosec.control.sdk.ControlRefusedException].
 *
 * The caller is stated in `CoSec-Identity`, written for each call: a [me.ahoo.cosec.control.sdk.ControlCaller.of]
 * caller as the gateway forwards that request's principal, a [me.ahoo.cosec.control.sdk.ControlCaller.service] caller as
 * the identity the in-process implementation admits for it. The header carries no signature: the host believes it
 * because only the services it trusts can reach its service port, which the host's own statement says
 * (`cosec.pipeline.identity.only-trusted-callers-can-reach`). It still authorizes and records every call as the principal
 * the header names.
 *
 * [client] carries every call: its TLS (a client certificate for a service port that asks for one), proxy, connect
 * timeout and executor are the application's to choose.
 *
 * What a call answers:
 * - a `2xx`: the route's answer;
 * - any other status, a redirect included (the default client follows none): a
 *   [me.ahoo.cosec.control.sdk.ControlRefusedException] carrying that status and the body as it came, a problem or not;
 * - no answer within [timeout]: [java.net.http.HttpTimeoutException]. The host may have done what was asked: the outcome
 *   of a write that timed out is unknown, so read the change or the document again before retrying. A change it left
 *   staged is taken over by the next [me.ahoo.cosec.control.sdk.ControlClient.proposeAndPublishWithoutShadow] of the same
 *   principal once it is one patience old;
 * - any other failure of the transport (a refused connection, a reset): the [java.io.IOException] as the client throws
 *   it. This is the one way the two implementations differ: in process, the control plane is not behind a connection
 *   (`CTL-77`).
 */
@ExperimentalCoSecApi
class HttpControlService(
    /** The control API's base on the host's service port, such as `http://control:8082/cosec/control/v1`. */
    baseUri: URI,
    client: HttpClient = HttpClient.newHttpClient(),
    /**
     * How long a call waits for its answer: a host that stops answering without closing the connection never holds a
     * caller longer. The same as the write call's patience by default.
     */
    timeout: Duration = DEFAULT_TIMEOUT,
    /** The host's `cosec.control.app-id`: a service caller's identity is in the control plane's app. */
    app: String = CONTROL_APP,
) : ControlService by HttpTransport(baseUri, client, timeout, app) {
    @ExperimentalCoSecApi
    companion object {
        /** The control plane's app by default (`cosec.control.app-id`). */
        const val CONTROL_APP: String = "cosec-control"

        /** How long a call waits for its answer by default. */
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
