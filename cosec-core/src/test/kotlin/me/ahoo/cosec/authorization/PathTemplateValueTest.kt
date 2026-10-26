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

package me.ahoo.cosec.authorization

import io.mockk.every
import io.mockk.mockk
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.request.AppIdCapable
import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.parity.appPermissionJson
import me.ahoo.cosec.parity.permissionJson
import me.ahoo.cosec.parity.policyJson
import me.ahoo.cosec.permission.AppRolePermissionData
import me.ahoo.cosec.permission.RolePermissionData
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.EnumSource
import reactor.core.publisher.Mono

/**
 * A `#{...}` value in a `path` action fills one literal segment and nothing else: a value that would be pattern
 * syntax (a wildcard, a capture, a regex), span segments or be empty is rejected. A rejected value never grants
 * (ALLOW: implicit deny, not an error) and never stops a DENY (DENY: the statement matches every path, so the
 * principal is denied wherever the policy applies).
 */
@ParameterizedClass
@EnumSource(AuthorizationImpl::class)
class PathTemplateValueTest(private val impl: AuthorizationImpl) {
    companion object {
        /** Each one once widened a templated ALLOW, narrowed a DENY, or failed the request with an error. */
        val REJECTED = listOf(
            "*", "{x}", "{x:.*}", "?????*", "{a}{b}", "**", "{*x}", "{", "a/b", "victim/..", "", ".", "..",
            "*@github", "{x:[0-9]+}",
        )
    }

    private fun policy(statements: String): Policy =
        CoSecJsonSerializer.readValue(policyJson("g", "global", statements), Policy::class.java)

    private fun authorization(
        global: List<Policy> = emptyList(),
        appPermission: AppPermission? = null,
        granted: Set<String> = setOf("allow", "deny")
    ): DecisionAuthorization = impl.create(
        mockk {
            every { getGlobalPolicy() } returns Mono.just(global)
            every { getPolicies(any()) } returns Mono.just(emptyList())
        },
        mockk {
            every { getAppRolePermission(any(), any(), any()) } returns Mono.justOrEmpty(
                appPermission?.let { AppRolePermissionData(it, listOf(RolePermissionData("r", granted))) }
            )
        },
    )

    private fun DecisionAuthorization.decide(
        path: String,
        id: String,
        attributes: Map<String, Any> = emptyMap()
    ): AuthorizeResult {
        val principal = SimplePrincipal(id, roles = setOf("r"), attributes = attributes)
        val request = EvaluateRequest(path = path, method = "GET", headers = mapOf(AppIdCapable.APP_ID_KEY to "app"))
        return checkNotNull(decide(request, SimpleSecurityContext(principal)).block()).result
    }

    private val ownPath = policy("""{"name":"own","action":"/users/#{principal.id}/**"}""")

    @Test
    fun rejectedIdsDoNotReachAnotherUsersPath() {
        val authorization = authorization(listOf(ownPath))
        (REJECTED + "alice").forEach { id ->
            authorization.decide("/users/victim/orders", id).assert().describedAs("id [$id]")
                .isEqualTo(AuthorizeResult.IMPLICIT_DENY)
        }
        authorization.decide("/users/a/b/orders", "a/b").assert().isEqualTo(AuthorizeResult.IMPLICIT_DENY)
        authorization.decide("/users/victim/orders", "victim").assert().isEqualTo(AuthorizeResult.ALLOW)
        authorization.decide("/users/alice/orders", "alice").assert().isEqualTo(AuthorizeResult.ALLOW)
    }

    @Test
    fun rejectedAttributesDoNotReachAnotherTeamsPath() {
        val authorization =
            authorization(listOf(policy("""{"name":"team","action":"/teams/#{principal.attributes['team']}/**"}""")))
        (REJECTED + "alpha").forEach { team ->
            authorization.decide("/teams/victim/orders", "user", mapOf("team" to team)).assert()
                .describedAs("team [$team]").isEqualTo(AuthorizeResult.IMPLICIT_DENY)
        }
        authorization.decide("/teams/victim/orders", "user").assert().isEqualTo(AuthorizeResult.IMPLICIT_DENY)
        authorization.decide("/teams/victim/orders", "user", mapOf("team" to "victim")).assert()
            .isEqualTo(AuthorizeResult.ALLOW)
    }

    @Test
    fun aRejectedValueInATemplatedDenyDenies() {
        val authorization = authorization(
            listOf(
                policy(
                    """{"name":"api","action":"/api/**"},""" +
                        """{"name":"blocked","effect":"deny","action":"/api/blocked/#{principal.id}/**"}""",
                ),
            ),
        )
        REJECTED.forEach { id ->
            listOf("/api/blocked/victim/data", "/api/public").forEach { path ->
                authorization.decide(path, id).assert().describedAs("id [$id] $path")
                    .isEqualTo(AuthorizeResult.EXPLICIT_DENY)
            }
        }
        authorization.decide("/api/blocked/alice/data", "alice").assert().isEqualTo(AuthorizeResult.EXPLICIT_DENY)
        authorization.decide("/api/blocked/victim/data", "alice").assert().isEqualTo(AuthorizeResult.ALLOW)
        authorization.decide("/api/public", "alice").assert().isEqualTo(AuthorizeResult.ALLOW)
    }

    @Test
    fun aCaptureInTheIdDoesNotEscapeItsOwnDeny() {
        val authorization = authorization(
            listOf(
                policy(
                    """{"name":"users","action":"/users/**"},""" +
                        """{"name":"no-delete","effect":"deny","action":"/users/#{principal.id}/delete"}""",
                ),
            ),
        )
        authorization.decide("/users/{x:[0-9]+}/delete", "{x:[0-9]+}").assert()
            .isEqualTo(AuthorizeResult.EXPLICIT_DENY)
        authorization.decide("/users/1/delete", "{x:[0-9]+}").assert().isEqualTo(AuthorizeResult.EXPLICIT_DENY)
        authorization.decide("/users/alice/delete", "alice").assert().isEqualTo(AuthorizeResult.EXPLICIT_DENY)
        authorization.decide("/users/alice/view", "alice").assert().isEqualTo(AuthorizeResult.ALLOW)
    }

    @Test
    fun rolePermissionsFollowTheirEffect() {
        val appPermission = CoSecJsonSerializer.readValue(
            appPermissionJson(
                "app",
                permissionJson("allow", "/users/#{principal.id}/**") + "," +
                    permissionJson("deny", "/api/blocked/#{principal.id}/**", effect = "deny"),
            ),
            AppPermission::class.java,
        )
        val authorization = authorization(appPermission = appPermission)
        REJECTED.forEach { id ->
            authorization.decide("/users/victim/orders", id).assert().describedAs("id [$id]")
                .isEqualTo(AuthorizeResult.EXPLICIT_DENY)
        }
        authorization.decide("/users/alice/orders", "alice").assert().isEqualTo(AuthorizeResult.ALLOW)
        authorization.decide("/users/victim/orders", "alice").assert().isEqualTo(AuthorizeResult.IMPLICIT_DENY)
    }

    @Test
    fun aRejectedValueInAnAllowOnlyPermissionIsAnImplicitDeny() {
        val appPermission = CoSecJsonSerializer.readValue(
            appPermissionJson("app", permissionJson("allow", "/users/#{principal.id}/**")),
            AppPermission::class.java,
        )
        val authorization = authorization(appPermission = appPermission, granted = setOf("allow"))
        REJECTED.forEach { id ->
            authorization.decide("/users/victim/orders", id).assert().describedAs("id [$id]")
                .isEqualTo(AuthorizeResult.IMPLICIT_DENY)
        }
    }
}
