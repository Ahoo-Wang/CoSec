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
import me.ahoo.cosec.api.authorization.Authorization
import me.ahoo.cosec.api.authorization.AuthorizeResult
import me.ahoo.cosec.api.context.request.AppIdCapable
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.context.SecurityContextParser
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.context.request.RequestParser
import me.ahoo.cosec.limiter.LocalRateLimiter
import me.ahoo.cosec.limiter.RateLimitRule
import me.ahoo.cosec.limiter.RateLimitRuleProvider
import me.ahoo.cosec.limiter.RateLimitingAuthorization
import me.ahoo.cosec.parity.appPermissionJson
import me.ahoo.cosec.parity.permissionJson
import me.ahoo.cosec.parity.policyJson
import me.ahoo.cosec.permission.AppRolePermissionData
import me.ahoo.cosec.permission.RolePermissionData
import me.ahoo.cosec.pipeline.SecurityPipeline
import me.ahoo.cosec.pipeline.SecurityStatus
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.action.RejectedTemplateValueException
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.EnumSource
import reactor.core.publisher.Mono
import java.net.URLEncoder

/**
 * A `#{...}` value in a `path` action fills one literal segment and nothing else: a value that would be pattern
 * syntax (a wildcard, a capture, a regex), span segments or be empty is rejected. A rejected value never grants:
 * in an ALLOW the action does not match (implicit deny, not an error); in a DENY or a rate limit rule the request
 * fails closed with [RejectedTemplateValueException], whatever the condition, so neither can stop applying.
 */
@ParameterizedClass
@EnumSource(AuthorizationImpl::class)
class PathTemplateValueTest(private val impl: AuthorizationImpl) {
    companion object {
        /** Each one once widened a templated ALLOW, narrowed a DENY, or failed the request with an error. */
        val REJECTED = listOf(
            "*", "{x}", "{x:.*}", "?????*", "{a}{b}", "**", "{*x}", "{", "}", "?", "a/b", "victim/..", "", ".", "..",
            "*@github", "{x:[0-9]+}", "a\\b",
        )

        /** Ids that matched both statements of [docsPolicy] before the fix, so its DENY decided. */
        val BLOCKER_IDS = listOf("*", "{x}", "*@github")

        /** The id as one encoded path segment, so a path variable captures it as written. */
        fun segment(id: String): String = URLEncoder.encode(id, Charsets.UTF_8).replace("+", "%20")
    }

    private fun policy(statements: String): Policy =
        CoSecJsonSerializer.readValue(policyJson("g", "global", statements), Policy::class.java)

    /**
     * Users reach their own documents, except `secret`. With a rejected id, a DENY that matched without capturing
     * `doc` would fail its condition and let the ALLOW through.
     */
    private val docsPolicy = policy(
        """{"name":"own-docs","action":"/users/{uid}/docs/{doc}",""" +
            """"condition":{"eq":{"part":"request.path.var.uid","value":"#{principal.id}"}}},""" +
            """{"name":"no-secret","effect":"deny","action":"/users/#{principal.id}/docs/{doc}",""" +
            """"condition":{"eq":{"part":"request.path.var.doc","value":"secret"}}}""",
    )

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

    private fun request(path: String) =
        EvaluateRequest(path = path, method = "GET", headers = mapOf(AppIdCapable.APP_ID_KEY to "app"))

    private fun context(id: String, attributes: Map<String, Any> = emptyMap()) =
        SimpleSecurityContext(SimplePrincipal(id, roles = setOf("r"), attributes = attributes))

    private fun Authorization.decide(
        path: String,
        id: String,
        attributes: Map<String, Any> = emptyMap()
    ): AuthorizeResult = checkNotNull(authorize(request(path), context(id, attributes)).block())

    private fun Authorization.assertFailsClosed(path: String, id: String) {
        assertThrows<RejectedTemplateValueException>("id [$id] $path") { decide(path, id) }
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
    fun aRejectedValueInATemplatedDenyFailsClosed() {
        val authorization = authorization(
            listOf(
                policy(
                    """{"name":"api","action":"/api/**"},""" +
                        """{"name":"blocked","effect":"deny","action":"/api/blocked/#{principal.id}/**"}""",
                ),
            ),
        )
        REJECTED.forEach { id ->
            listOf(
                "/api/blocked/victim/data",
                "/api/public"
            ).forEach { path -> authorization.assertFailsClosed(path, id) }
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
        authorization.assertFailsClosed("/users/${segment("{x:[0-9]+}")}/delete", "{x:[0-9]+}")
        authorization.assertFailsClosed("/users/1/delete", "{x:[0-9]+}")
        authorization.decide("/users/alice/delete", "alice").assert().isEqualTo(AuthorizeResult.EXPLICIT_DENY)
        authorization.decide("/users/alice/view", "alice").assert().isEqualTo(AuthorizeResult.ALLOW)
    }

    @Test
    fun aDenyConditionOnAPathVariableStillDecides() {
        val authorization = authorization(listOf(docsPolicy))
        BLOCKER_IDS.forEach { id ->
            listOf("secret", "public").forEach { doc ->
                authorization.assertFailsClosed("/users/${segment(id)}/docs/$doc", id)
            }
        }
        authorization.decide("/users/alice/docs/secret", "alice").assert().isEqualTo(AuthorizeResult.EXPLICIT_DENY)
        authorization.decide("/users/alice/docs/public", "alice").assert().isEqualTo(AuthorizeResult.ALLOW)
        authorization.decide("/users/victim/docs/public", "alice").assert().isEqualTo(AuthorizeResult.IMPLICIT_DENY)
    }

    @Test
    fun theSecurityPipelineMapsARejectedDenyValueToError() {
        val pipeline = SecurityPipeline(
            object : RequestParser<Request> {
                override fun parse(request: Request): Request = request
            },
            SecurityContextParser { context("*") },
            authorization(listOf(docsPolicy)),
        )
        val decision = checkNotNull(pipeline.evaluate(request("/users/%2A/docs/secret")).block())
        decision.status.assert().isEqualTo(SecurityStatus.ERROR)
        decision.result.authorized.assert().isFalse()
    }

    @Test
    fun aRejectedValueInARateLimitRuleFailsClosedAndSpendsNoPermit() {
        val rule = CoSecJsonSerializer.readValue(
            """{"id":"export","action":"/users/#{principal.id}/docs/{doc}",""" +
                """"condition":{"eq":{"part":"request.path.var.doc","value":"export"}},"permitsPerSecond":1}""",
            RateLimitRule::class.java,
        )
        val allowAll = policy("""{"name":"all","action":"*"}""")
        val authorization = RateLimitingAuthorization(
            authorization(listOf(allowAll)),
            RateLimitRuleProvider.of(listOf(rule)),
            LocalRateLimiter(),
        )
        BLOCKER_IDS.forEach { id -> authorization.assertFailsClosed("/users/${segment(id)}/docs/export", id) }
        // The key-less bucket is shared: the rejected callers above spent none of it.
        authorization.decide("/users/alice/docs/export", "alice").assert().isEqualTo(AuthorizeResult.ALLOW)
        authorization.decide("/users/alice/docs/export", "alice").assert()
            .isEqualTo(AuthorizeResult.TOO_MANY_REQUESTS)
        authorization.decide("/users/alice/docs/view", "alice").assert().isEqualTo(AuthorizeResult.ALLOW)
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
        REJECTED.forEach { id -> authorization.assertFailsClosed("/users/victim/orders", id) }
        authorization.decide("/users/alice/orders", "alice").assert().isEqualTo(AuthorizeResult.ALLOW)
        authorization.decide("/api/blocked/alice/x", "alice").assert().isEqualTo(AuthorizeResult.EXPLICIT_DENY)
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
