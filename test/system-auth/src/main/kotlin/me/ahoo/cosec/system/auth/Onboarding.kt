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

package me.ahoo.cosec.system.auth

import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper

/**
 * An onboarding the IAM does for a tenant, with no person behind it: `POST /admin/onboard` with `{"tenant", "policy"}`
 * puts an empty-handed policy into the tenant's document through the control plane ([IamControl]), skips the shadow
 * window and publishes. The platform document permits it to the platform administrator `root` only, and the gateways
 * have no route to it: the scenarios call it on this host's own port. It answers the published change, or the answer
 * of the step the control plane refused.
 */
@RestController
class Onboarding(private val control: IamControl) {
    private val json = JsonMapper.builder().build()

    @PostMapping("/admin/onboard")
    suspend fun onboard(@RequestBody body: Map<String, String>): ResponseEntity<String> {
        val tenant = body.getValue("tenant")
        val key = "tenant:$tenant"
        val head = control.document(key).also { if (it.status != OK) return refused(it) }
        val revision = json.readTree(head.body).get("revision").asLong()
        val request = json.writeValueAsString(
            mapOf(
                "document" to key,
                "base" to revision,
                "note" to "onboarding of $tenant",
                "operations" to listOf(
                    mapOf(
                        "op" to "putPolicy",
                        "policy" to mapOf(
                            "id" to body.getValue("policy"),
                            "statements" to listOf(
                                mapOf(
                                    "name" to "pages",
                                    "effect" to "allow",
                                    "action" to "http:GET",
                                    "resource" to listOf("http:path:/behind/${body.getValue("policy")}/**"),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val proposed = control.propose(request).also { if (it.status != CREATED) return refused(it) }
        val id = json.readTree(proposed.body).get("id").asString()
        control.skipShadow(id, "onboarding of $tenant").also { if (it.status != OK) return refused(it) }
        return refused(control.publish(id))
    }

    private fun refused(answer: ControlAnswer): ResponseEntity<String> =
        ResponseEntity.status(HttpStatusCode.valueOf(answer.status)).header("Content-Type", "application/json").body(answer.body)

    private companion object {
        const val OK = 200
        const val CREATED = 201
    }
}
