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

package me.ahoo.cosec.control.local

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.api.ControlHttp
import me.ahoo.cosec.control.api.ControlHttpRequest
import me.ahoo.cosec.control.api.ControlPlane
import me.ahoo.cosec.control.api.ControlProfile
import me.ahoo.cosec.control.authz.PipelineControlAuthorizer
import me.ahoo.cosec.control.sdk.ControlCaller
import me.ahoo.cosec.control.sdk.ControlRefusedException
import me.ahoo.cosec.control.sdk.RoutedControlService
import me.ahoo.cosec.control.sdk.ServiceCall
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.http.RequestSecurity
import me.ahoo.cosec.runtime.identity.IdentityGate
import me.ahoo.cosec.runtime.identity.ServiceIdentity
import me.ahoo.cosec.spi.id.IdGenerator
import me.ahoo.cosec.spi.id.RandomIdGenerator
import java.time.Clock

/**
 * [me.ahoo.cosec.control.sdk.ControlService] in the application's own process (`CTL-65`): each call is the control API's route itself, handed
 * to [ControlHttp] of the service profile ([ControlProfile.SERVICE], the one definition of what a service reaches,
 * which the service port serves too) with the caller's request security, so it is authorized, recorded and answered as
 * that route is, by one code path, and recorded `via.method: in-process` (`CTL-57`). A [ControlCaller.service] caller is
 * admitted by [IdentityGate.admit], the function the gate reads a forwarded identity with, as a short-lived identity of
 * the platform tenant.
 */
@InternalCoSecApi
class LocalControlService(
    plane: ControlPlane,
    /** Admits a service caller as the gate admits a forwarded identity: same expiry, age, tenant and scope. */
    private val gate: IdentityGate,
    /** The control plane's app (`cosec.control.app-id`): a service caller's identity is in it. */
    private val app: AppId = PipelineControlAuthorizer.CONTROL_APP,
    private val clock: Clock = Clock.systemUTC(),
    private val ids: IdGenerator = RandomIdGenerator,
) : RoutedControlService() {
    /** The service profile's API over [plane]: a call through this facade holds its work in the plane's budget (`CTL-85`). */
    val http = ControlHttp(plane, ControlProfile.SERVICE)

    override suspend fun call(caller: ControlCaller, call: ServiceCall): String {
        val request = ControlHttpRequest(
            call.method,
            call.path,
            call.query,
            call.body?.encodeToByteArray(),
            inProcess = true
        )
        val response = http.handle(request, security(caller))
        if (response.status >= BAD_REQUEST) throw refusal(response)
        return response.body.decodeToString()
    }

    /**
     * The request security [caller] acts with: its request's, or a service identity the gate admits now. A request whose
     * identity was forwarded without `memberships` is refused as malformed (400), as a host that decides on the identity
     * refuses the header (`IDN-39`) and as the relay leaves the member out for it (`IDN-42`): the gate read only its own
     * space's roles and no attachment, which is not the caller's whole memberships, so the control plane does not decide
     * on it in process either (`CTL-77`).
     */
    private fun security(caller: ControlCaller): RequestSecurity {
        caller.context?.let { context ->
            val security = requireNotNull(RequestSecurity.of(context)) { ControlCaller.NOT_COSEC_CONTEXT }
            val forwarded = security.identity
            if (forwarded != null && forwarded.memberships == null) {
                throw refusal(http.refused(BAD_REQUEST, security.requestId))
            }
            return security
        }
        val requestId = ids.generate()
        val identity = ServiceIdentity.of(
            checkNotNull(caller.principal),
            caller.roles,
            app.value,
            clock.instant(),
            requestId
        )
        return when (val admission = gate.admit(identity, requestId, FACTS)) {
            is IdentityGate.Admission.Admitted -> admission.security
            is IdentityGate.Admission.Refused -> throw refusal(http.refused(UNAUTHORIZED, requestId))
        }
    }

    private fun refusal(response: me.ahoo.cosec.control.api.ControlHttpResponse) =
        ControlRefusedException(response.status, response.body.decodeToString())

    private companion object {
        const val BAD_REQUEST = 400
        const val UNAUTHORIZED = 401

        /** The facts of an in-process call: no client, no headers; the gate reads its scope against no host app. */
        val FACTS = HttpFacts(
            method = RoutedControlService.POST,
            rawPath = "/",
            rawQuery = null,
            headers = Headers.EMPTY,
            cookies = Cookies.EMPTY,
            remoteAddress = "in-process",
            host = HostScope(null),
        )
    }
}
