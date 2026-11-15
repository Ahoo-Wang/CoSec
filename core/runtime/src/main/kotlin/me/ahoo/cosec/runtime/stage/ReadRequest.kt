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
import me.ahoo.cosec.api.audit.AuditRequest
import me.ahoo.cosec.document.route.RouteMap
import me.ahoo.cosec.document.route.RouteResolution
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Identifiers
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.cosec.runtime.http.CoSecParameters
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.http.HttpMethods
import me.ahoo.cosec.runtime.http.Outcome
import me.ahoo.cosec.runtime.http.PathNormalizer
import me.ahoo.cosec.runtime.http.StatusMapping

/**
 * Stage 1: what the request asks for. Its normalized path (400 when ambiguous, PIP-5, PIP-14), its `CoSec-*`
 * parameters (400 when ambiguous), its method (400 with a lower-case letter, PIP-18), and the action and resource of
 * its route (400 for a capture that would change the resource id's segments). It writes nothing until all of them
 * are read, so a request refused here names none of them in its event.
 */
internal class ReadRequest(private val routes: RouteMap) : Stage {
    override suspend fun run(state: RequestState): Outcome? {
        val facts = routed(state.input)
        val path = PathNormalizer.normalize(facts.rawPath) ?: return invalid(state, "path")
        val parameters = CoSecParameters.read(facts) ?: return invalid(state, "CoSec-* parameter")
        // PIP-18: a method holding a lower-case letter is no method any client sends on purpose, and backends that fold
        // case (Django, Werkzeug) would serve `delete` as DELETE past a DENY on `http:DELETE`: refused, as an ambiguous
        // path is. HEAD is GET without the content, and servers run the GET handler for it: decided as GET.
        if (HttpMethods.hasLowerCase(facts.method)) {
            return invalid(state, "method")
        }
        when (val route = routes.resolve(facts.method, path)) {
            RouteResolution.Unmapped -> {
                state.action = Action(HTTP, HttpMethods.decidedAs(facts.method))
                state.resource = Resource(HTTP, path, emptyMap())
            }
            is RouteResolution.Mapped -> {
                state.action = route.action
                state.resource = route.resource
            }
            // A capture that would change the resource id's segments: refused, never read as another resource.
            is RouteResolution.Invalid -> return invalid(state, "path")
        }
        state.facts = facts
        state.path = path
        state.parameters = parameters
        return null
    }

    private fun invalid(state: RequestState, what: String): Outcome =
        StatusMapping.invalidRequest(state.requestId, what, state.traceId)

    /**
     * [facts] with the gateway route's app as the host app (RC1-6), under the app id rule (RC1-15). One that is not a
     * string, or cannot be an app id, is an operator's mistake no request can be decided under: it throws, so the
     * request is an unmodelled error (500), logged, never a request without a host app, whose client would then pick
     * the app and step around the app's DENYs. A number is not read as its text: `cosec-app-id: 0123` would not be the
     * app `0123`.
     */
    private fun routed(facts: HttpFacts): HttpFacts {
        val app = facts.routeApp ?: return facts
        if (app !is String || !ScopeResolver.isValidAppId(app)) {
            invalidRouteApp(facts, app)
        }
        return HttpFacts(
            facts.method,
            facts.rawPath,
            facts.rawQuery,
            facts.headers,
            facts.cookies,
            facts.remoteAddress,
            HostScope(AppId(app)),
            facts.requestAttributes,
        )
    }

    /**
     * Why [routed] refuses [facts], whose route app is [app]: kept out of it, so the path every request takes stays
     * small. The value itself is not written: its type, or why the string cannot be an app id, says what to fix.
     */
    private fun invalidRouteApp(facts: HttpFacts, app: Any): Nothing {
        val routeId = Identifiers.printable(facts.requestAttributes[AuditRequest.ROUTE_ID_ATTRIBUTE_KEY].orEmpty())
        val route = "The cosec-app-id of route [$routeId]"
        error(
            if (app is String) {
                "$route cannot be an app id: blank, *, with ':', a control character or an unpaired surrogate, or " +
                    "longer than ${ScopeResolver.MAX_APP_ID_LENGTH} characters."
            } else {
                "$route is a ${app.javaClass.name}, not a string: an app id is a string. Quote it in the route's " +
                    "metadata (cosec-app-id: \"<app id>\"), as a single value."
            }
        )
    }

    private companion object {
        const val HTTP = "http"
    }
}
