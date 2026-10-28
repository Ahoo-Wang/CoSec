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

package me.ahoo.cosec.runtime.authn

import kotlinx.coroutines.test.runTest
import me.ahoo.cosec.apikey.ApiKey
import me.ahoo.cosec.apikey.ApiKeyReader
import me.ahoo.cosec.apikey.ApiKeys
import me.ahoo.cosec.apikey.InMemoryApiKeyStore
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** RFC 0006 §5.3.2: an API key is judged as the 7.x `ApiKeySecurityContextParser` judges it. */
class ApiKeyAuthenticatorTest {
    private val now: Instant = Instant.parse("2026-11-01T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val billing = ApiKey(
        id = "billing",
        principalId = "svc-billing",
        tenantId = "acme",
        roles = setOf("billing", "auditor@team-a"),
        policies = setOf("p1"),
        attributes = mapOf("team" to "finance"),
        expiresAt = now.plusSeconds(60),
    )
    private val keys = mapOf(
        "valid" to billing,
        "disabled" to billing.copy(id = "disabled", enabled = false),
        "expired" to billing.copy(id = "expired", expiresAt = now),
        "no-tenant" to billing.copy(id = "no-tenant", tenantId = null),
    )
    private val authenticator = ApiKeyAuthenticator(
        InMemoryApiKeyStore(keys.mapKeys { (key, _) -> ApiKeys.hash(key) }).reader,
        clock,
    )

    private suspend fun authenticate(key: String?) = authenticator.authenticate(Credentials("token", key))

    @Test
    fun withoutAKeyTheRequestIsAnonymous() = runTest {
        authenticate(null).assert().isSameAs(AuthnResult.Anonymous)
    }

    @Test
    fun aValidKeyIsItsPrincipal() = runTest {
        val result = authenticate("valid") as AuthnResult.Authenticated
        result.method.assert().isEqualTo(ApiKeyAuthenticator.METHOD)
        val principal = result.principal
        principal.id.assert().isEqualTo(PrincipalId("svc-billing"))
        principal.tenant.assert().isEqualTo(TenantId("acme"))
        principal.attributes.assert().containsEntry("team", "finance")
        principal.attributes.assert().containsEntry(ApiKey.API_KEY_ID_ATTRIBUTE, "billing")
        val memberships = principal.memberships!!
        memberships.roles[SpaceId.DEFAULT]!!.toList().assert().containsExactly(RoleId("billing"))
        memberships.roles[SpaceId("team-a")]!!.toList().assert().containsExactly(RoleId("auditor"))
        memberships.attachments.assert().containsExactly(PolicyId("p1"))
    }

    @Test
    fun aKeyWithoutTenantIsInTheDefaultTenant() = runTest {
        (authenticate("no-tenant") as AuthnResult.Authenticated).principal.tenant.assert().isEqualTo(TenantId.DEFAULT)
    }

    @Test
    fun unknownDisabledAndExpiredKeysAreRejected() = runTest {
        listOf("unknown", "disabled", "expired").forEach {
            val rejected = authenticate(it) as AuthnResult.Rejected
            rejected.reason.assert().isEqualTo(AuthnResult.RejectReason.API_KEY_INVALID)
        }
    }

    /** Fail closed: a store that cannot be read reaches the pipeline, which answers 503; it never rejects or accepts. */
    @Test
    fun aFailingStorePropagates() = runTest {
        val failing = ApiKeyAuthenticator(ApiKeyReader { throw IllegalStateException("store down") }, clock)
        val error = try {
            failing.authenticate(Credentials(null, "valid"))
            null
        } catch (e: IllegalStateException) {
            e
        }
        error!!.message.assert().isEqualTo("store down")
    }
}
