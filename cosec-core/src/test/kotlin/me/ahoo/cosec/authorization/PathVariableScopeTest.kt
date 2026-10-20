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
import me.ahoo.cosec.api.configuration.Configuration
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.api.permission.AppPermission
import me.ahoo.cosec.api.permission.PermissionGroup
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.policy.Statement
import me.ahoo.cosec.configuration.JsonConfiguration
import me.ahoo.cosec.configuration.JsonConfiguration.Companion.asConfiguration
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.explain.AuthorizationExplainer
import me.ahoo.cosec.explain.ExplainOptions
import me.ahoo.cosec.limiter.LocalRateLimitRuleLoader
import me.ahoo.cosec.limiter.LocalRateLimiter
import me.ahoo.cosec.limiter.RateLimit
import me.ahoo.cosec.limiter.RateLimitRule
import me.ahoo.cosec.limiter.RateLimitRuleProvider
import me.ahoo.cosec.limiter.RateLimiter
import me.ahoo.cosec.limiter.RateLimitingAuthorization
import me.ahoo.cosec.permission.DefaultAppPermissionEvaluator
import me.ahoo.cosec.policy.DefaultPolicyEvaluator
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.LocalPolicyLoader
import me.ahoo.cosec.policy.PathVariableScopeException
import me.ahoo.cosec.policy.PolicyData
import me.ahoo.cosec.policy.StatementData
import me.ahoo.cosec.policy.action.AllActionMatcher
import me.ahoo.cosec.policy.action.PathActionMatcherFactory
import me.ahoo.cosec.policy.action.getPathVariables
import me.ahoo.cosec.policy.condition.AllConditionMatcher
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import reactor.core.publisher.Mono
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Path variables belong to the statement (or rate limit rule) whose own `path` action captured them.
 *
 * Every scenario once leaked: a matcher that did not run its own path action read whatever the last matching path
 * matcher left in the shared context. Readers the load-time check recognizes are rejected; for readers it cannot
 * recognize (a custom condition matcher reading the variables in code), the variables are cleared before each
 * statement, so they read nothing.
 */
class PathVariableScopeTest {
    private val request = EvaluateRequest(path = "/orders/1", method = "GET")

    private fun policy(json: String): Policy = CoSecJsonSerializer.readValue(json, Policy::class.java)

    private fun authorization(global: List<Policy>, principal: List<Policy> = listOf()) = SimpleAuthorization(
        policyRepository(global, principal),
        mockk<AppRolePermissionRepository> {
            every { getAppRolePermission(any(), any(), any()) } returns Mono.empty()
        },
    )

    // The principal lists exactly the policies the repository returns: a listed policy the repository lacks fails
    // authorization (MissingPolicyException), which is not what these tests are about.
    private var principalPolicyIds: Set<String> = emptySet()

    private fun policyRepository(global: List<Policy>, principal: List<Policy>): PolicyRepository {
        principalPolicyIds = principal.map { it.id }.toSet()
        return mockk {
            every { getGlobalPolicy() } returns Mono.just(global)
            every { getPolicies(any()) } returns Mono.just(principal)
        }
    }

    private val principal = SimplePrincipal("user", policies = setOf("p"))

    private fun context() = SimpleSecurityContext(SimplePrincipal("user", policies = principalPolicyIds))

    private fun path(pattern: String) = PathActionMatcherFactory.INSTANCE.create(pattern.asConfiguration())

    /** Reads `orderId` in code, which the load-time check cannot see. */
    private object OrderIdIsOne : ConditionMatcher {
        override val type: String = "orderIdIsOne"
        override val configuration: Configuration = JsonConfiguration.newPojoConfiguration()
        override fun match(request: Request, securityContext: SecurityContext): Boolean =
            securityContext.getPathVariables()?.get("orderId") == "1"
    }

    private fun globalPolicy(vararg statements: Statement, condition: ConditionMatcher? = null) = PolicyData(
        id = "g",
        category = "",
        name = "g",
        description = "",
        type = PolicyType.GLOBAL,
        tenantId = "t",
        statements = statements.toList(),
        condition = condition ?: AllConditionMatcher.INSTANCE,
    )

    private fun customPolicy(vararg statements: Statement, condition: ConditionMatcher) = PolicyData(
        id = "p",
        category = "",
        name = "p",
        description = "",
        type = PolicyType.CUSTOM,
        tenantId = "t",
        statements = statements.toList(),
        condition = condition,
    )

    // Never matches, but its action captures orderId=1 on the way.
    private val unrelatedDeny = StatementData(
        name = "unrelated-deny",
        effect = Effect.DENY,
        action = path("/orders/{orderId}"),
        condition = CoSecJsonSerializer.readValue(
            """{ "eq": { "part": "request.path.var.orderId", "value": "never" } }""",
            ConditionMatcher::class.java,
        ),
    )

    private val unrelatedDenyJson = """
        { "name": "unrelated-deny", "effect": "deny", "action": "/orders/{orderId}",
          "condition": { "eq": { "part": "request.path.var.orderId", "value": "never" } } }
    """

    private fun policyJson(id: String, type: String, statements: String, condition: String? = null) = """
        { "id": "$id", "name": "$id", "category": "", "description": "", "type": "$type", "tenantId": "t",
          ${condition?.let { "\"condition\": $it," } ?: ""} "statements": [ $statements ] }
    """

    private fun authorize(authorization: SimpleAuthorization) = authorization.authorize(request, context()).block()

    // Scenario 1: a statement only sees the variables of its own action.

    @Test
    fun allActionStatementReadingPathVariablesFailsToLoad() {
        val allowAllWhenOrder1 = """
            { "name": "all-when-order-1", "effect": "allow", "action": { "all": {} },
              "condition": { "eq": { "part": "request.path.var.orderId", "value": "1" } } }
        """
        assertThrownBy<PathVariableScopeException> {
            policy(policyJson("g", "global", "$unrelatedDenyJson, $allowAllWhenOrder1"))
        }.hasMessageContaining("Policy [g] statement [1][all-when-order-1] condition")
            .hasMessageContaining("[all] action captures none")
    }

    @Test
    fun addingADenyDoesNotWidenAccess() {
        val allowAllWhenOrder1 = StatementData(action = AllActionMatcher.INSTANCE, condition = OrderIdIsOne)
        // Control: alone, the all-action statement reads no orderId and does not allow.
        authorize(authorization(listOf(globalPolicy(allowAllWhenOrder1))))
            .assert().isEqualTo(AuthorizeResult.IMPLICIT_DENY)
        // A deny that never matches must not widen access by leaving its orderId behind.
        authorize(authorization(listOf(globalPolicy(unrelatedDeny, allowAllWhenOrder1))))
            .assert().isEqualTo(AuthorizeResult.IMPLICIT_DENY)
    }

    @Test
    fun pathStatementOnlySeesVariablesItCaptured() {
        // Passes the load-time check (its action is a path); its own pattern captures no orderId, so it reads empty.
        val allowWithoutOrderId = policyJson(
            "g",
            "global",
            """$unrelatedDenyJson, { "effect": "allow", "action": "/orders/*",
               "condition": { "eq": { "part": "request.path.var.orderId", "value": "1" } } }""",
        )
        authorize(authorization(listOf(policy(allowWithoutOrderId))))
            .assert().isEqualTo(AuthorizeResult.IMPLICIT_DENY)
    }

    @Test
    fun pathStatementStillSeesItsOwnVariables() {
        val allowOrder1 = policyJson(
            "g",
            "global",
            """$unrelatedDenyJson, { "effect": "allow", "action": "/orders/{orderId}",
               "condition": { "eq": { "part": "request.path.var.orderId", "value": "1" } } }""",
        )
        authorize(authorization(listOf(policy(allowOrder1)))).assert().isEqualTo(AuthorizeResult.ALLOW)
    }

    // Scenario 2: a policy condition runs before any action, so it sees no variables.

    @Test
    fun policyConditionReadingPathVariablesFailsToLoad() {
        assertThrownBy<PathVariableScopeException> {
            policy(
                policyJson(
                    "p",
                    "custom",
                    """{ "effect": "allow", "action": { "all": {} } }""",
                    condition = """{ "eq": { "part": "request.path.var.orderId", "value": "1" } }""",
                ),
            )
        }.hasMessageContaining("Policy [p] condition reads path variables")
    }

    @Test
    fun policyConditionDoesNotSeeVariablesOfAnotherLayer() {
        val principalPolicy = customPolicy(StatementData(action = AllActionMatcher.INSTANCE), condition = OrderIdIsOne)
        authorize(authorization(listOf(), listOf(principalPolicy))).assert().isEqualTo(AuthorizeResult.IMPLICIT_DENY)
        authorize(authorization(listOf(globalPolicy(unrelatedDeny)), listOf(principalPolicy)))
            .assert().isEqualTo(AuthorizeResult.IMPLICIT_DENY)
    }

    @Test
    fun explanationAgreesWithDecision() {
        val global =
            listOf(
                globalPolicy(unrelatedDeny, StatementData(action = AllActionMatcher.INSTANCE, condition = OrderIdIsOne))
            )
        val principalPolicies =
            listOf(customPolicy(StatementData(action = AllActionMatcher.INSTANCE), condition = OrderIdIsOne))
        val explanation = AuthorizationExplainer(
            policyRepository(global, principalPolicies),
            mockk<AppRolePermissionRepository>(),
        ).explain(request, principal, ExplainOptions(exhaustive = true)).block()!!
        authorize(authorization(global, principalPolicies)).assert().isEqualTo(AuthorizeResult.IMPLICIT_DENY)
        explanation.authorized.assert().isFalse()
        explanation.decidedBy.assert().isNull()
    }

    // Scenario 3: a rate limit rule only sees the variables of its own action.

    @Test
    fun rateLimitKeyWithoutPathActionFailsToLoad() {
        assertThrownBy<PathVariableScopeException> {
            RateLimitRule(
                id = "per-order",
                action = AllActionMatcher.INSTANCE,
                rateLimit = RateLimit(permitsPerSecond = 1.0),
                key = "request.path.var.orderId",
            )
        }.hasMessageContaining("Rate limit rule [per-order] key")
        assertThrownBy<PathVariableScopeException> {
            RateLimitRule(
                id = "per-order",
                action = AllActionMatcher.INSTANCE,
                rateLimit = RateLimit(permitsPerSecond = 1.0),
                condition = OrderIdConditionJson.condition(),
            )
        }.hasMessageContaining("Rate limit rule [per-order] condition")
    }

    @Test
    fun rateLimitKeyDoesNotSeeVariablesOfPolicies() {
        fun bucketsOf(rule: RateLimitRule): List<String> {
            val acquired = CopyOnWriteArrayList<String>()
            val local = LocalRateLimiter(clock = { 0 })
            val limiter = RateLimiter { id, bucket, limit ->
                acquired.add("$id/$bucket")
                local.tryAcquire(id, bucket, limit)
            }
            val allowOrders = globalPolicy(StatementData(action = path("/orders/{orderId}")))
            RateLimitingAuthorization(
                authorization(listOf(allowOrders)),
                RateLimitRuleProvider.of(listOf(rule)),
                limiter
            )
                .authorize(request, context()).block()
            return acquired
        }
        val rateLimit = RateLimit(permitsPerSecond = 1.0)
        // Control: the rule's own action captures orderId.
        bucketsOf(RateLimitRule("per-order", path("/orders/{orderId}"), rateLimit, key = "request.path.var.orderId"))
            .assert().containsExactly("per-order/g:1")
        // A rule without a path action sees no variables, whatever the policy captured.
        bucketsOf(RateLimitRule("order-1", AllActionMatcher.INSTANCE, rateLimit, condition = OrderIdIsOne))
            .assert().isEmpty()
    }

    // What the load-time check recognizes, and where.

    @Test
    fun expressionsReadingPathVariablesFailToLoad() {
        listOf(
            """{ "ognl": { "expression": "context.attributes['PATH_VARIABLES']['orderId'] == '1'" } }""",
            """{ "spel": { "expression": "context.getAttributeValue('PATH_VARIABLES') != null" } }""",
            """{ "eq": { "part": "request.path", "value": "#{attributes['PATH_VARIABLES']['orderId']}" } }""",
            """{ "bool": { "and": [ { "in": { "part": "request.path.var.orderId", "value": ["1"] } } ] } }""",
            """{ "regular": { "negate": true, "part": "request.path.var.orderId", "pattern": "^1$" } }""",
        ).forEach { condition ->
            assertThrownBy<PathVariableScopeException> {
                policy(policyJson("g", "global", """{ "action": "*", "condition": $condition }"""))
            }
            // A statement with its own path action may read them in any of these forms.
            policy(policyJson("g", "global", """{ "action": "/orders/{orderId}", "condition": $condition }"""))
        }
    }

    @Test
    fun onlyActionsThatAlwaysCaptureMayReadPathVariables() {
        val condition = OrderIdConditionJson
        // An array of patterns is a path action; ["*", ...] is the all action.
        policy(
            policyJson(
                "g",
                "global",
                """{ "action": ["/orders/{orderId}", "/o/{orderId}"], "condition": $condition }"""
            )
        )
        policy(
            policyJson(
                "g",
                "global",
                """{ "action": { "composite": [ "/orders/{orderId}", { "path": { "pattern": "/o/{orderId}" } } ] },
                   "condition": $condition }""",
            ),
        )
        assertThrownBy<PathVariableScopeException> {
            policy(policyJson("g", "global", """{ "action": ["*", "/orders/{orderId}"], "condition": $condition }"""))
        }
        assertThrownBy<PathVariableScopeException> {
            policy(
                policyJson(
                    "g",
                    "global",
                    """{ "action": { "composite": [ "/orders/{orderId}", { "all": {} } ] }, "condition": $condition }""",
                ),
            )
        }
    }

    @Test
    fun appPermissionsAreChecked() {
        assertThrownBy<PathVariableScopeException> {
            CoSecJsonSerializer.readValue(
                """{ "id": "app", "condition": $OrderIdConditionJson, "groups": [] }""",
                AppPermission::class.java,
            )
        }.hasMessageContaining("App permission [app] condition")
        assertThrownBy<PathVariableScopeException> {
            CoSecJsonSerializer.readValue(
                """{ "id": "app", "groups": [ { "name": "g", "description": "", "permissions": [
                     { "id": "read", "name": "read", "action": "*", "condition": $OrderIdConditionJson } ] } ] }""",
                AppPermission::class.java,
            )
        }.hasMessageContaining("App permission [app] permission [read] condition")
    }

    @Test
    fun customImplementationsAreCheckedByTheEvaluators() {
        val leaking = StatementData(action = AllActionMatcher.INSTANCE, condition = OrderIdConditionJson.condition())
        val customPolicy = object : Policy {
            override val id = "custom"
            override val name = "custom"
            override val category = ""
            override val description = ""
            override val type = PolicyType.GLOBAL
            override val tenantId = "t"
            override val condition: ConditionMatcher = AllConditionMatcher.INSTANCE
            override val statements: List<Statement> = listOf(leaking)
        }
        assertThrownBy<PathVariableScopeException> { DefaultPolicyEvaluator.evaluate(customPolicy) }
        val customAppPermission = object : AppPermission {
            override val id = "app"
            override val condition: ConditionMatcher = OrderIdConditionJson.condition()
            override val groups: List<PermissionGroup> = listOf()
        }
        assertThrownBy<PathVariableScopeException> { DefaultAppPermissionEvaluator.evaluate(customAppPermission) }
    }

    @Test
    fun localPolicyFailsLoadingInsteadOfBeingSkipped(@TempDir dir: File) {
        dir.resolve("leaking-policy.json").writeText(
            policyJson("g", "global", """{ "action": "*", "condition": $OrderIdConditionJson }"""),
        )
        assertThrownBy<IllegalStateException> {
            LocalPolicyLoader(setOf("file:${dir.absolutePath}/*.json")).policies
        }.hasMessageContaining("leaking-policy.json").hasMessageContaining("Policy [g] statement [0]")
    }

    @Test
    fun localRateLimitRuleFailsLoading(@TempDir dir: File) {
        dir.resolve("leaking-rate-limit.json").writeText(
            """{ "id": "per-order", "action": "*", "key": "request.path.var.orderId", "permitsPerSecond": 1 }""",
        )
        assertThrownBy<IllegalStateException> {
            LocalRateLimitRuleLoader(setOf("file:${dir.absolutePath}/*.json")).getRules()
        }.hasRootCauseInstanceOf(PathVariableScopeException::class.java)
    }

    private object OrderIdConditionJson {
        override fun toString(): String = """{ "eq": { "part": "request.path.var.orderId", "value": "1" } }"""

        fun condition(): ConditionMatcher = CoSecJsonSerializer.readValue(toString(), ConditionMatcher::class.java)
    }
}
