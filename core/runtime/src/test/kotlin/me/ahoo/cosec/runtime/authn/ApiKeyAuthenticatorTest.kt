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
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.apikey.ApiKey
import me.ahoo.cosec.spi.apikey.ApiKeyReader
import me.ahoo.cosec.spi.apikey.ApiKeys
import me.ahoo.cosec.spi.apikey.InMemoryApiKeyStore
import me.ahoo.cosec.spi.authn.AuthnResult
import me.ahoo.cosec.spi.authn.Credentials
import me.ahoo.cosec.spi.authn.MembershipSource
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** RFC 0006 §5.3.2, RFC 0009 §4.2: an API key proves its principal; the membership provider says what it may do. */
class ApiKeyAuthenticatorTest {
    private val now: Instant = Instant.parse("2026-11-01T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private fun key(
        id: String,
        roles: Set<String>? = setOf("billing", "auditor@team-a"),
        expiresAt: Instant? = now.plusSeconds(60),
        enabled: Boolean = true,
    ) = ApiKey(id, "svc-billing", "acme", roles, expiresAt, enabled)

    private val keys = mapOf(
        "valid" to key("billing"),
        "uncapped" to key("uncapped", roles = null),
        "empty" to key("empty", roles = emptySet()),
        "disabled" to key("disabled", enabled = false),
        "expired" to key("expired", expiresAt = now),
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
        principal.attributes.assert().isEqualTo(mapOf(ApiKey.API_KEY_ID_ATTRIBUTE to "billing"))
        // IDN-1: the key claims nothing; the provider answers, narrowed to the key's roles.
        principal.memberships.assert().isNull()
        val source = principal.membershipSource as MembershipSource.Provider
        source.cap.assert().isEqualTo(
            mapOf(SpaceId.DEFAULT to setOf(RoleId("billing")), SpaceId("team-a") to setOf(RoleId("auditor"))),
        )
    }

    /** `IDN-2`: an empty cap is a cap that leaves nothing, never no cap. */
    @Test
    fun anEmptyCapIsNotUncapped() = runTest {
        val principal = (authenticate("empty") as AuthnResult.Authenticated).principal
        (principal.membershipSource as MembershipSource.Provider).cap.assert().isNotNull().isEmpty()
    }

    @Test
    fun aKeyWithoutRolesIsNotCapped() = runTest {
        val principal = (authenticate("uncapped") as AuthnResult.Authenticated).principal
        (principal.membershipSource as MembershipSource.Provider).cap.assert().isNull()
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
