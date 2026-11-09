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

package me.ahoo.cosec.runtime

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.audit.AuditRequest
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.spi.authn.AuthenticatedPrincipal
import me.ahoo.cosec.spi.authn.Authenticator
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * SCP-2, RC1-6: a gateway route's `cosec-app-id` is the host app of its requests, ahead of the configured one; the
 * gateway passes it as written, and the pipeline checks it under the app id rule (RC1-15). One that cannot be an app id
 * fails the request closed: 500, never a request without a host app, whose client would pick the app.
 */
class RouteAppTest {
    private val engine = PipelineFixtures.StubEngine(PipelineFixtures.PERMIT)
    private var credentialApp: String? = "billing"
    private val pipeline = PipelineFixtures.pipeline(
        Authenticator {
            AuthnResult.Authenticated(
                AuthenticatedPrincipal(
                    PipelineFixtures.PRINCIPAL,
                    PipelineFixtures.TENANT,
                    PipelineFixtures.claims(),
                    app = credentialApp?.let(::AppId),
                ),
                "test",
            )
        },
        engine,
    )

    private fun facts(routeApp: Any?, host: String? = "shop") = HttpFacts(
        method = "GET",
        rawPath = "/orders",
        rawQuery = null,
        headers = Headers.EMPTY,
        cookies = Cookies.EMPTY,
        remoteAddress = "10.0.0.1",
        host = HostScope(host?.let(::AppId)),
        requestAttributes = mapOf(AuditRequest.ROUTE_ID_ATTRIBUTE_KEY to "billing\nroute"),
        routeApp = routeApp,
    )

    private fun evaluate(facts: HttpFacts): Outcome = runBlocking { pipeline.evaluate(facts) }

    @Test
    fun `the route's app is the host app, ahead of the configured one`() {
        evaluate(facts("billing")).proceed.assert().isTrue()
        engine.requests.last().scope.app.value.assert().isEqualTo("billing")
        // A credential of another app is rejected, as against a configured host app.
        credentialApp = "shop"
        val other = evaluate(facts("billing"))
        other.status.assert().isEqualTo(403)
        other.problem!!.type.assert().isEqualTo(ProblemType.SCOPE_REJECTED)
        // Without a route app the configured one applies, and without either the credential's.
        evaluate(facts(null)).proceed.assert().isTrue()
        engine.requests.last().scope.app.value.assert().isEqualTo("shop")
        credentialApp = "order"
        evaluate(facts(null, host = null)).proceed.assert().isTrue()
        engine.requests.last().scope.app.value.assert().isEqualTo("order")
    }

    @Test
    fun `a route app that cannot be an app id fails the request closed, and says why`() {
        for (app in listOf("", " ", "*", "a:b", "a\nb", "x".repeat(129))) {
            val before = engine.requests.size
            val outcome = evaluate(facts(app))
            outcome.status.assert().describedAs(app).isEqualTo(500)
            outcome.problem!!.type.assert().isEqualTo(ProblemType.INTERNAL_ERROR)
            engine.requests.size.assert().describedAs(app).isEqualTo(before)
            // What the log line says: the route, with its control characters escaped, and why.
            val error = (outcome.basis as Basis.Error).error
            error.message.assert().contains("route [billing\\u000Aroute]").contains("cannot be an app id")
                .doesNotContain("\n")
        }
    }

    /**
     * R1-7 of #1194's review: only a string is a route app. A number, a list or a map from the route's metadata is not
     * read as its text (`[billing]`, `{id=billing}` would be a host app no document has): the same 500, decided here,
     * whose log line names the route, the type and how to fix it, and not the value.
     */
    @Test
    fun `a route app that is not a string fails the request closed, and says to quote it`() {
        val values = listOf<Any>(123, 1.5, true, listOf("billing"), mapOf("id" to "billing"), mapOf("0" to "billing"))
        for (app in values) {
            val before = engine.requests.size
            val outcome = evaluate(facts(app))
            outcome.status.assert().describedAs("$app").isEqualTo(500)
            outcome.problem!!.type.assert().isEqualTo(ProblemType.INTERNAL_ERROR)
            engine.requests.size.assert().describedAs("$app").isEqualTo(before)
            val error = (outcome.basis as Basis.Error).error
            error.message.assert().contains(
                "route [billing\\u000Aroute]"
            ).contains("is a ${app.javaClass.name}, not a string")
                .contains("Quote it").doesNotContain("[billing]").doesNotContain("id=billing").doesNotContain("123")
        }
    }
}
