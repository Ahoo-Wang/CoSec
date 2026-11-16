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
import me.ahoo.cosec.control.change.InvalidControlRequestException
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.runtime.http.RequestSecurity
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.JsonNode

/**
 * A path parameter is percent-decoded once, after the path is split into segments (#1385). Path decoding, not form
 * decoding: `+` is a `+`.
 */
class PathSegmentsTest {
    @Test
    fun `a value is decoded once`() {
        mapOf(
            "acme" to "acme",
            "acme%20west" to "acme west",
            "%E4%B8%AD%E6%96%87" to "中文",
            "100%25" to "100%",
            "a+b" to "a+b",
            "a%2Bb" to "a+b",
            "acme%2520west" to "acme%20west",
            "tenant%3Aacme" to "tenant:acme",
            "a%2Fb" to "a/b",
            "%f0%9F%98%80" to "😀",
            // What the client sent unescaped is itself.
            "中文" to "中文",
            "😀%21" to "😀!",
            "" to "",
        ).forEach { (raw, decoded) -> unescaped(raw).assert().describedAs(raw).isEqualTo(decoded) }
    }

    @Test
    fun `a malformed escape is refused, never read as it is`() {
        listOf("%", "%2", "%zz", "a%2", "%2z", "%-1", "%+1", "%C3%28", "%C3", "%FF", "%ED%A0%80", "%C0%AF").forEach {
            assertThrows<InvalidControlRequestException>(it) { unescaped(it) }
        }
    }
}

/** The route table over a real control plane: what decoding hands on, and what it must not change. */
class ControlHttpPathDecodingTest {
    private val fixture = ControlPlaneTest()
    private val http = ControlHttp(fixture.plane)
    private val alice = fixture.engine.security(ControlPlaneTest.ACME, "alice", roles = listOf("admin", "ops@team-a"))

    private val plato = fixture.engine.security(TenantId.PLATFORM, "plato")

    private fun get(path: String, operator: RequestSecurity = alice): Pair<Int, JsonNode> = runBlocking {
        val response = http.handle(ControlHttpRequest("GET", path, emptyMap(), null), operator)
        response.status to CoSecJson.mapper.readTree(response.body)
    }

    @Test
    fun `an encoded key prefix names the same document`() {
        val (plain, document) = get("/documents/tenant:acme")
        plain.assert().isEqualTo(200)
        val (encoded, again) = get("/documents/tenant%3Aacme")
        encoded.assert().describedAs("$again").isEqualTo(200)
        again.assert().isEqualTo(document)
        get("/documents/tenant:acme/revisions/%31").first.assert().isEqualTo(200)
    }

    /**
     * Decoding makes `acme/west` the tenant id, which no tenant id may hold (#1403): it names no document, for any
     * principal, before anything is authorized or read. An encoded `/` is never a second route segment.
     */
    @Test
    fun `an encoded slash is one parameter, and an id with a slash is still refused`() {
        val (status, problem) = get("/documents/tenant:acme%2Fwest", plato)
        status.assert().describedAs("$problem").isEqualTo(400)
        problem.get("detail").asString().assert().contains("names no document").contains("holds no ':' or '/'")
        get(
            "/documents/tenant:acme%2Fwest"
        ).first.assert().describedAs("a tenant's own principal is refused too").isEqualTo(400)
        get("/documents/tenant:acme/west", plato).first.assert().isEqualTo(404)
    }

    @Test
    fun `a malformed escape is a 400, and the route's own words are never escapes`() {
        listOf(
            "/documents/tenant:acme%zz",
            "/documents/tenant:acme%2",
            "/documents/tenant:acme%C3%28",
            "/changes/c%"
        ).forEach {
            get(it).first.assert().describedAs(it).isEqualTo(400)
        }
        // `documents`, the verbs and `revisions` are matched as written: an escape of them is no route.
        listOf("/%64ocuments/tenant:acme", "/documents/tenant:acme/%72evisions").forEach {
            get(it).first.assert().describedAs(it).isEqualTo(404)
        }
    }
}
