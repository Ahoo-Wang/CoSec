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

package me.ahoo.cosec.control.api

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** DR1-B-2: a document field that does not read is a 400 naming it, never a 500 that logs a defect. */
class ControlFieldErrorTest {
    private val fixture = ControlPlaneTest()
    private val http = ControlHttp(fixture.plane)
    private val alice = fixture.engine.security(ControlPlaneTest.ACME, "alice", roles = listOf("admin", "ops@team-a"))

    @Test
    fun `an expiresAt past year 9999 is refused as a field, not answered as an error`() = runBlocking<Unit> {
        val proposal = """{"document":"tenant:acme","base":1,"note":"far","operations":[""" +
            """{"op":"putPolicy","policy":{"id":"far","statements":[{"effect":"allow","action":"order:read"}]}},""" +
            """{"op":"putBinding","binding":{"id":"far","subject":{"role":"viewer"},"app":"order","space":"*",""" +
            """"policy":"far","expiresAt":"+999999999-12-31T23:59:59Z"}}]}"""
        val response = http.handle(ControlHttpRequest("POST", "/changes", emptyMap(), proposal.encodeToByteArray()), alice)
        response.status.assert().isEqualTo(400)
        CoSecJson.mapper.readTree(response.body).get("detail").asString().assert().contains("expiresAt")
            .contains("year 9999")
    }
}
