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
 * What the IAM does for a tenant with no person behind it, through the control plane ([IamControl]), as its own principal:
 *
 * - `POST /admin/onboard` with `{"tenant", "policy"}`: the tenant's document is created, when it does not exist, with one
 *   policy that is bound to nobody, or the policy is put into the document that exists; the shadow window is skipped and the
 *   change published. It grants no one anything, so no one need approve it. It answers the published change.
 * - `POST /admin/grant` with `{"tenant", "policy", "role"}`: a change that puts the policy and binds it to a role of the
 *   tenant, and skips the window. A grant by a principal of the platform tenant in a tenant's document is a person's to
 *   approve (`CROSS_TENANT`), so it answers the change waiting for one (`AWAITING_APPROVAL`) and publishes nothing.
 *
 * The platform document permits both to the platform administrator `root` only, and the gateways route them: a scenario
 * calls them as `root`. A step the control plane refuses is answered as the control plane answered it.
 */
@RestController
class Onboarding(private val control: IamControl) {
    private val json = JsonMapper.builder().build()

    @PostMapping("/admin/onboard")
    suspend fun onboard(@RequestBody body: Map<String, String>): ResponseEntity<String> {
        val (tenant, policy) = ids(body)
        val key = "tenant:$tenant"
        val head = control.document(key)
        val change = when (head.status) {
            OK -> propose(key, json.readTree(head.body).get("revision").asLong(), tenant, policy, null)
            NOT_FOUND -> control.apply(key, document(tenant, policy))
            else -> return refused(head)
        }
        if (change.status != CREATED) return refused(change)
        val id = json.readTree(change.body).get("id").asString()
        control.skipShadow(id, "onboarding of $tenant").also { if (it.status != OK) return refused(it) }
        return refused(control.publish(id))
    }

    @PostMapping("/admin/grant")
    suspend fun grant(@RequestBody body: Map<String, String>): ResponseEntity<String> {
        val (tenant, policy) = ids(body)
        val role = body.getValue("role")
        require(ID.matches(role)) { "A role id is letters, digits and dashes." }
        val key = "tenant:$tenant"
        val head = control.document(key)
        if (head.status != OK) return refused(head)
        val proposed = propose(key, json.readTree(head.body).get("revision").asLong(), tenant, policy, role)
        if (proposed.status != CREATED) return refused(proposed)
        val id = json.readTree(proposed.body).get("id").asString()
        return refused(control.skipShadow(id, "a grant for $role in $tenant"))
    }

    private suspend fun propose(key: String, revision: Long, tenant: String, policy: String, role: String?): ControlAnswer {
        val operations = buildList {
            add(mapOf("op" to "putPolicy", "policy" to policyOf(policy)))
            role?.let {
                add(
                    mapOf(
                        "op" to "putBinding",
                        "binding" to mapOf(
                            "id" to "$it-$policy",
                            "subject" to mapOf("role" to it),
                            "app" to "*",
                            "space" to "*",
                            "policy" to policy,
                        ),
                    ),
                )
            }
        }
        return control.propose(
            json.writeValueAsString(
                mapOf(
                    "document" to key,
                    "base" to revision,
                    "note" to "onboarding of $tenant",
                    "operations" to operations
                ),
            ),
        )
    }

    /** A new tenant's document: one policy, bound to nobody. */
    private fun document(tenant: String, policy: String): String = json.writeValueAsString(
        mapOf(
            "document" to mapOf(
                "schema" to SCHEMA,
                "revision" to 1,
                "publishedAt" to "2026-11-21T00:00:00Z",
                "policies" to listOf(policyOf(policy)),
            ),
            "mode" to "MERGE",
            "note" to "onboarding of $tenant",
        ),
    )

    private fun policyOf(policy: String): Map<String, Any> = mapOf(
        "id" to policy,
        "statements" to listOf(
            mapOf(
                "name" to "pages",
                "effect" to "allow",
                "action" to "http:GET",
                "resource" to listOf("http:path:/behind/$policy/**"),
            ),
        ),
    )

    /** The tenant and the policy id of [body]: ids of this test host's own scenarios, nothing that could name another route. */
    private fun ids(body: Map<String, String>): Pair<String, String> {
        val tenant = body.getValue("tenant")
        val policy = body.getValue("policy")
        require(ID.matches(tenant) && ID.matches(policy)) { "A tenant and a policy id are letters, digits and dashes." }
        return tenant to policy
    }

    private fun refused(answer: ControlAnswer): ResponseEntity<String> =
        ResponseEntity.status(
            HttpStatusCode.valueOf(answer.status)
        ).header("Content-Type", "application/json").body(answer.body)

    private companion object {
        val ID = Regex("[A-Za-z0-9-]{1,64}")
        const val SCHEMA = 2
        const val OK = 200
        const val CREATED = 201
        const val NOT_FOUND = 404
    }
}
