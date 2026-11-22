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

@file:OptIn(ExperimentalCoSecApi::class)

package me.ahoo.cosec.system.auth

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.control.sdk.Binding
import me.ahoo.cosec.control.sdk.Change
import me.ahoo.cosec.control.sdk.ChangeRequest
import me.ahoo.cosec.control.sdk.ControlCaller
import me.ahoo.cosec.control.sdk.ControlClient
import me.ahoo.cosec.control.sdk.ControlRefusedException
import me.ahoo.cosec.control.sdk.ControlService
import me.ahoo.cosec.control.sdk.Effect
import me.ahoo.cosec.control.sdk.Operation
import me.ahoo.cosec.control.sdk.Policy
import me.ahoo.cosec.control.sdk.Resource
import me.ahoo.cosec.control.sdk.Statement
import me.ahoo.cosec.control.sdk.Subject
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import org.springframework.http.HttpStatusCode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper

/**
 * What the IAM does for a tenant with no person behind it, through the control plane, as its own principal `svc-iam` of the
 * platform tenant: the typed calls ([ControlClient]) over the application's one `ControlService`, which the starter makes
 * from configuration (`STA-11`). It is the in-process implementation where the IAM embeds the control plane, and the HTTP
 * one, over a control-plane host's service port, where `cosec.control.service-url` names the host. The code is the same in
 * both deployments.
 *
 * - `POST /admin/onboard` with `{"tenant", "policy"}`: one policy, bound to nobody, put into the tenant's document (created
 *   when there is none), the shadow window skipped and the change published: the write a projector makes
 *   ([ControlClient.proposeAndPublishWithoutShadow]). It grants no one anything, so no one need approve it. It answers the
 *   published change.
 * - `POST /admin/grant` with `{"tenant", "policy", "role"}`: a change that puts the policy and binds it to a role of the
 *   tenant, and skips the window. A grant by a principal of the platform tenant in a tenant's document is a person's to
 *   approve (`CROSS_TENANT`), so it answers the change waiting for one (`AWAITING_APPROVAL`) and publishes nothing.
 *
 * An answer is the change's `id`, `state`, `candidate` and `escalations`; a step the control plane refuses is answered as
 * the control plane answered it. The platform document permits both routes to the platform administrator `root` only,
 * and the gateways route them: a scenario calls them as `root`.
 */
@RestController
class Onboarding(service: ControlService) {
    private val control = ControlClient(service)
    private val iam = ControlCaller.service(SERVICE_PRINCIPAL)
    private val json = JsonMapper.builder().build()

    @PostMapping("/admin/onboard")
    suspend fun onboard(@RequestBody body: Map<String, String>): ResponseEntity<String> {
        val (tenant, policy) = ids(body)
        return answer {
            control.proposeAndPublishWithoutShadow(
                iam,
                SnapshotKey.Tenant(tenant),
                listOf(Operation.PutPolicy(policyOf(policy))),
                "onboarding of $tenant",
                "onboarding of $tenant",
            )
        }
    }

    @PostMapping("/admin/grant")
    suspend fun grant(@RequestBody body: Map<String, String>): ResponseEntity<String> {
        val (tenant, policy) = ids(body)
        val role = body.getValue("role")
        require(ID.matches(role)) { "A role id is letters, digits and dashes." }
        val key = SnapshotKey.Tenant(tenant)
        return answer {
            val head = control.document(iam, key)
            val operations = listOf(
                Operation.PutPolicy(policyOf(policy)),
                Operation.PutBinding(Binding("$role-$policy", Subject.Role(role), "*", "*", policy)),
            )
            val proposed = control.propose(iam, ChangeRequest(key, head.revision, operations, "onboarding of $tenant"))
            control.skipShadow(iam, proposed.id, "a grant for $role in $tenant")
        }
    }

    private suspend fun answer(call: suspend () -> Change): ResponseEntity<String> {
        val change = try {
            call()
        } catch (refused: ControlRefusedException) {
            return ResponseEntity.status(HttpStatusCode.valueOf(refused.status))
                .header("Content-Type", "application/problem+json").body(refused.problem)
        }
        val answer = mapOf(
            "id" to change.id,
            "state" to change.state.text,
            "candidate" to change.candidate,
            "escalations" to change.escalations.map {
                mapOf("subject" to it.subject, "reason" to it.reason.text, "detail" to it.detail)
            },
        )
        return ResponseEntity.ok().header("Content-Type", "application/json").body(json.writeValueAsString(answer))
    }

    private fun policyOf(policy: String) = Policy(
        policy,
        listOf(
            Statement(
                Effect.ALLOW,
                listOf("http:GET"),
                listOf(Resource("http:path:/behind/$policy/**")),
                name = "pages",
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

    private companion object {
        val ID = Regex("[A-Za-z0-9-]{1,64}")
        const val SERVICE_PRINCIPAL = "svc-iam"
    }
}
