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

@file:OptIn(me.ahoo.cosec.annotations.InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.control.api

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.model.TenantId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * `CTL-58`: the service profile serves the tenant and app documents and the change flow that needs no person; anything
 * else is a 404 before the control plane is asked, for a principal that is allowed everything.
 */
class ControlProfileTest {
    private val fixture = ControlPlaneTest()
    private val plato = fixture.engine.security(TenantId.PLATFORM, "plato", policies = listOf("cosec:platform-admin"))
    private val operators = ControlHttp(fixture.plane)
    private val services = ControlHttp(fixture.plane, ControlProfile.SERVICE)

    private fun status(http: ControlHttp, method: String, path: String, body: String? = null): Int = runBlocking {
        http.handle(ControlHttpRequest(method, path, emptyMap(), body?.encodeToByteArray()), plato).status
    }

    @Test
    fun `the service profile serves a tenant document and its history, and the change flow`() {
        for (path in listOf(
            "/documents/tenant:acme",
            "/documents/tenant:acme/revisions",
            "/documents/tenant:acme/revisions/1",
            "/documents/tenant:acme/diff?from=1&to=1",
            "/changes",
            "/changes/c1",
        )) {
            status(services, "GET", path.substringBefore('?')).assert().describedAs("GET $path").isNotEqualTo(404)
        }
        for (path in listOf(
            "/changes",
            "/changes/c1:rebase",
            "/changes/c1:abandon",
            "/changes/c1:skip-shadow",
            "/changes/c1:publish",
            "/documents/tenant:acme:apply",
        )) {
            status(services, "POST", path, "{}").assert().describedAs("POST $path").isNotEqualTo(404)
        }
    }

    @Test
    fun `what is a person's or the platform's is a 404 on the service profile, and is served on the operators' one`() {
        val outside = listOf(
            "GET" to "/documents/platform",
            "GET" to "/documents/platform/revisions",
            "GET" to "/documents/tenant:acme/revisions/1/propagation",
            "POST" to "/documents/tenant:acme:rollback",
            "POST" to "/documents/app:order:apply",
            "POST" to "/documents/platform:apply",
            "POST" to "/changes/c1:approve",
            "POST" to "/changes/c1:reject",
            "POST" to "/break-glass",
            "POST" to "/break-glass/c1:approve",
            "DELETE" to "/break-glass/b1",
            "POST" to "/simulate",
            "POST" to "/explain",
            "POST" to "/analyze",
        )
        for ((method, path) in outside) {
            status(services, method, path, "{}").assert().describedAs("service $method $path").isEqualTo(404)
        }
        // The same requests are not 404 on the operators' profile (they are answered, or refused for what they hold).
        for ((method, path) in outside.filter { it.second != "/documents/tenant:acme/revisions/1/propagation" }) {
            status(operators, method, path, "{}").assert().describedAs("operators $method $path").isNotEqualTo(404)
        }
    }

    @Test
    fun `a proposal for the platform document is a 404 on the service profile, never reaching the control plane`() {
        val body = """{"document":"platform","base":1,"operations":[]}"""
        status(services, "POST", "/changes", body).assert().isEqualTo(404)
        status(operators, "POST", "/changes", body).assert().isNotEqualTo(404)
        // A tenant document is served, and refused for what the body lacks, not for being outside the profile.
        status(services, "POST", "/changes", """{"document":"tenant:acme","base":1,"operations":[]}""")
            .assert().isNotEqualTo(404)
    }

    @Test
    fun `the shapes the profile serves are exactly these, by method and path`() {
        val service = ControlProfile.SERVICE
        fun admits(method: String, path: String) = service.admits(method, path.trim('/').split('/'))
        // Reads of a tenant or app document, by their exact shapes.
        for (path in listOf(
            "documents/tenant:a",
            "documents/app:a",
            "documents/tenant:a/diff",
            "documents/tenant:a/revisions",
            "documents/tenant:a/revisions/3"
        )) {
            admits("GET", path).assert().describedAs("GET $path").isTrue()
            admits("POST", path).assert().describedAs("POST $path").isFalse()
        }
        for (path in listOf(
            "documents/tenant:a/other",
            "documents/tenant:a/revisions/3/x",
            "documents/tenant:a/diff/x",
        )) {
            admits("GET", path).assert().describedAs("GET $path").isFalse()
        }
        // Which tenant and app documents there are (CTL-68): a read of the list, never a write to it.
        admits("GET", "documents").assert().isTrue()
        admits("POST", "documents").assert().isFalse()
        // Only a tenant's document takes `:apply`, and a key the route cannot name is the route's own 400.
        admits("POST", "documents/tenant:a:apply").assert().isTrue()
        admits("POST", "documents/app:a:apply").assert().isFalse()
        admits("GET", "documents/tenant:a:apply").assert().isFalse()
        admits("POST", "documents/tenant:a:apply/x").assert().isFalse()
        admits("GET", "documents/nonsense").assert().isTrue()
        // The change flow: a listing and a proposal, one change, its verbs that need no person.
        admits("GET", "changes").assert().isTrue()
        admits("POST", "changes").assert().isTrue()
        admits("DELETE", "changes").assert().isFalse()
        admits("GET", "changes/c1").assert().isTrue()
        admits("POST", "changes/c1").assert().isFalse()
        admits("GET", "changes/c1:publish").assert().isFalse()
        admits("POST", "changes/c1:publish").assert().isTrue()
        admits("POST", "changes/c1:shadow").assert().isTrue()
        admits("GET", "changes/c1:shadow").assert().isFalse()
        admits("POST", "changes/c1:unknown").assert().isFalse()
        admits("GET", "changes/c1/x").assert().isFalse()
        // Anything else is not a route of the profile, and the operators' profile serves all of them.
        admits("GET", "simulate").assert().isFalse()
        ControlProfile.OPERATORS.admits("POST", listOf("break-glass")).assert().isTrue()
        service.admitsWrite(me.ahoo.cosec.spi.snapshot.SnapshotKey.Tenant("a")).assert().isTrue()
        // A proposal, a check, a change and a listing of a tenant's or an app's document; never the platform's.
        service.admitsWrite(me.ahoo.cosec.spi.snapshot.SnapshotKey.App("a")).assert().isTrue()
        service.admitsWrite(me.ahoo.cosec.spi.snapshot.SnapshotKey.Platform).assert().isFalse()
        service.admitsChange(me.ahoo.cosec.spi.snapshot.SnapshotKey.App("a")).assert().isTrue()
        service.admitsChange(me.ahoo.cosec.spi.snapshot.SnapshotKey.Platform).assert().isFalse()
        service.admitsListing(me.ahoo.cosec.spi.snapshot.SnapshotKey.App("a")).assert().isTrue()
        service.admitsListing(me.ahoo.cosec.spi.snapshot.SnapshotKey.Platform).assert().isFalse()
        service.admitsListing(null).assert().isTrue()
        ControlProfile.OPERATORS.admitsWrite(me.ahoo.cosec.spi.snapshot.SnapshotKey.Platform).assert().isTrue()
    }

    @Test
    fun `no principal is a 401 on the service profile as on the operators' one, before any route is looked at`() {
        val anonymous = runBlocking {
            services.handle(ControlHttpRequest("GET", "/documents/platform", emptyMap(), null), null).status
        }
        anonymous.assert().isEqualTo(401)
    }
}
