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

package me.ahoo.cosec.conformance

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.ForwardedIdentity
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.SecurityScope
import me.ahoo.cosec.api.principal.CoSecPrincipal
import me.ahoo.cosec.login.token.SwitchTenantAuthentication
import me.ahoo.cosec.login.token.SwitchTenantCredentials
import me.ahoo.cosec.login.token.TenantMembership
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.testkit.only
import me.ahoo.cosec.testkit.required
import me.ahoo.cosec.testkit.strings
import me.ahoo.cosec.testkit.text
import tools.jackson.databind.JsonNode
import kotlin.coroutines.cancellation.CancellationException

/**
 * A scenario's `"switch"` (PipelineRunner): the access token it signs is switched to `targetTenantId` through
 * [SwitchTenantAuthentication], whose `TenantSwitch` answers `answer` (`roles`, `app`), with the credentials naming
 * `targetAppId` when given. The request's context is the token's: its principal, tenant and app.
 *
 * ```
 * "switch": { "access": { "jti": "a1", "sub": "alice", "tenantId": "acme", "claims": { "sid": "f1", "auth_time": 1 } },
 *             "targetTenantId": "globex", "targetAppId": "order", "answer": { "roles": ["clerk"], "app": "order" } }
 * ```
 *
 * The outcome is the issued token's tenant and app (200), or 401 when the switch refuses.
 */
internal object ScenarioSwitch {
    fun switched(node: JsonNode): Outcome {
        node.only("access", "targetTenantId", "targetAppId", "answer")
        val access = ScenarioJwt(node.required("access")).token
        val token = ScenarioJwt.VERIFIER.verify(access)
        val answer = node.get("answer") ?: tools.jackson.databind.node.JsonNodeFactory.instance.objectNode()
        answer.only("roles", "app")
        val membership = TenantMembership(
            roles = answer.get("roles")?.strings()?.toSet().orEmpty(),
            app = answer.get("app")?.text(),
        )
        val credentials = Credentials(
            targetTenantId = node.required("targetTenantId").text(),
            targetAppId = node.get("targetAppId")?.text(),
            context = context(token.principal, token.tenant ?: TenantId.DEFAULT.value, token.app.orEmpty()),
            accessToken = access,
        )
        val switch = SwitchTenantAuthentication(ScenarioJwt.VERIFIER, Credentials::class.java) { membership }
        return try {
            val subject = runBlocking { switch.authenticate(credentials) }
            Outcome.Answered(200, true, null, issued = mapOf("tenant" to subject.tenant, "app" to subject.app))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Outcome.Answered(401, false, "switch-refused")
        }
    }

    private class Credentials(
        override val targetTenantId: String,
        override val targetAppId: String?,
        override val context: SecurityContext,
        override val accessToken: String,
    ) : SwitchTenantCredentials

    private fun context(principalId: String, tenant: String, app: String) = object : SecurityContext {
        override val tenantId: String = tenant
        override val principal: CoSecPrincipal = object : CoSecPrincipal {
            override val id: String = principalId
            override val authenticated: Boolean = true
            override val attributes: Map<String, Any> = emptyMap()
        }
        override val scope: SecurityScope = object : SecurityScope {
            override val appId: String = app
            override val spaceId: String = ""
            override val roles: List<String> = emptyList()
        }
        override val forwardedIdentity: ForwardedIdentity? = null
    }
}
