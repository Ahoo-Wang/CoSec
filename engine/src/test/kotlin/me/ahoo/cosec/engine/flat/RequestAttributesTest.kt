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

package me.ahoo.cosec.engine.flat

import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Environment
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.Subject
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.ConditionInput
import me.ahoo.cosec.model.condition.IpAddress
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.scope.DeclaredScope
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.cosec.model.scope.ScopeSubject
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** What conditions read (RFC 0005 §3.2): the engine answers from the request what it holds, the host the rest. */
class RequestAttributesTest {
    /** Answers every attribute it is asked for, so a test sees when the engine asks the host for one it holds. */
    private object Lying : ConditionInput {
        override fun string(attribute: Attribute): String = "lie"

        override fun bool(attribute: Attribute): Boolean = true

        override fun stringSet(attribute: Attribute): Set<String> = setOf("lie")

        override fun ip(attribute: Attribute): IpAddress? = IpAddress.parse("10.9.9.9")

        override fun time(attribute: Attribute): Long = 42
    }

    private fun request(
        authenticated: Boolean = true,
        app: String = "app",
        remoteIp: String = "10.0.0.1",
        deviceId: String = "dev",
        path: String = "/orders/7",
        host: ConditionInput = Lying,
    ): FlatRequest {
        val memberships = Memberships(
            Memberships.Source.CLAIMS,
            if (authenticated) {
                mapOf(SpaceId.DEFAULT to setOf(RoleId("viewer")), SpaceId("team") to setOf(RoleId("editor")))
            } else {
                emptyMap()
            },
            emptyList(),
        )
        val tenant = if (authenticated) TenantId("acme") else TenantId.DEFAULT
        val resolution = ScopeResolver.resolve(
            ScopeSubject(tenant, authenticated, memberships),
            DeclaredScope(app, ""),
            HostScope(null)
        )
        val authz = AuthzRequest(
            Subject("alice", tenant.value, authenticated, emptySet(), emptySet(), mapOf("level" to 3), false),
            me.ahoo.cosec.model.Scope.declared(tenant.value, app, ""),
            Action("http", "GET"),
            Resource("http", path, mapOf("owner" to "alice")),
            Environment(remoteIp, "req", deviceId, blocked = false),
        )
        return FlatRequest(authz, (resolution as ScopeResolution.Resolved).scope, memberships, NOW, host)
    }

    /** Whether an ALLOW of `http:path:/orders/{id}` with [condition] permits [request]. */
    private fun permits(condition: Condition, request: FlatRequest = request()): Boolean {
        val policy = PolicyCompiler().compile(
            PolicyId("p"),
            null,
            listOf(
                StatementSource(
                    "s",
                    Effect.ALLOW,
                    listOf("*"),
                    listOf(ResourceSource("http:path:/orders/{id}")),
                    condition
                )
            ),
        )
        // Roles in a request with an app need the app's document, even an empty one.
        val app = request.scope.app.value.takeIf { it.isNotEmpty() }?.let { app(app = it) }
        val view =
            SnapshotView(
                platform(policy, bindings = listOf(anyone("g", "p"))),
                app,
                tenant(tenant = request.scope.tenant.value)
            )
        return CoSecFlatEngine.evaluate(request, view).decision is Decision.Permit
    }

    private fun eq(attribute: String, value: String, ignoreCase: Boolean = false) =
        Condition.Eq(attribute, Literal.Str(value), ignoreCase)

    private fun eq(attribute: String, value: Boolean) = Condition.Eq(attribute, Literal.Bool(value))

    private fun contains(attribute: String, value: String) = Condition.Contains(Literal.Str(value), attribute)

    @Test
    fun `the subject is the request's`() {
        permits(eq("subject.id", "alice")).assert().isTrue()
        permits(eq("subject.authenticated", true)).assert().isTrue()
        permits(eq("subject.tenant", "acme")).assert().isTrue()
        permits(eq("subject.attributes.level", "3")).assert().isTrue()
        permits(Condition.Exists("subject.attributes.other")).assert().isFalse()
        // An anonymous principal has no id, whatever the request carries.
        permits(Condition.Exists("subject.id"), request(authenticated = false)).assert().isFalse()
        permits(eq("subject.authenticated", false), request(authenticated = false)).assert().isTrue()
    }

    @Test
    fun `roles come from the memberships and the scope`() {
        permits(contains("subject.roles", "editor")).assert().isTrue()
        permits(contains("subject.scopedRoles", "editor@team")).assert().isTrue()
        permits(contains("subject.scopedRoles", "viewer")).assert().isTrue()
        permits(contains("scope.roles", "viewer")).assert().isTrue()
        permits(contains("scope.roles", "editor")).assert().isFalse()
        permits(
            Condition.And(listOf(contains("subject.roles", "viewer"), contains("subject.roles", "editor")))
        ).assert().isTrue()
    }

    @Test
    fun `the scope is the resolved one`() {
        permits(eq("scope.app", "app")).assert().isTrue()
        permits(eq("scope.tenant", "acme")).assert().isTrue()
        permits(eq("scope.space", "")).assert().isTrue()
        // No app is absent, not empty.
        permits(Condition.Exists("scope.app"), request(app = "")).assert().isFalse()
    }

    @Test
    fun `the action, the resource and its captures are the request's`() {
        permits(eq("action.service", "http")).assert().isTrue()
        permits(eq("action.verb", "GET")).assert().isTrue()
        permits(eq("resource.type", "http")).assert().isTrue()
        permits(eq("resource.id", "/orders/7")).assert().isTrue()
        permits(eq("resource.attributes.owner", "alice")).assert().isTrue()
        permits(eq("resource.var.id", "7")).assert().isTrue()
        permits(eq("resource.var.id", "8")).assert().isFalse()
        permits(eq("resource.var.id", "%41"), request(path = "/orders/%2541")).assert().isTrue()
    }

    @Test
    fun `the environment is the request's`() {
        permits(Condition.Cidr("environment.ip", listOf(Literal.Str("10.0.0.0/24")))).assert().isTrue()
        permits(Condition.Exists("environment.ip"), request(remoteIp = "not an address")).assert().isFalse()
        permits(Condition.Time(notBefore = "1970-01-01T00:00:00Z", notAfter = "1970-01-01T00:00:02Z")).assert().isTrue()
        permits(eq("environment.blocked", false)).assert().isTrue()
        permits(eq("environment.device", "dev")).assert().isTrue()
        permits(Condition.Exists("environment.device"), request(deviceId = "")).assert().isFalse()
    }

    @Test
    fun `the host answers the rest`() {
        permits(eq("http.header.x-trace", "lie")).assert().isTrue()
        permits(eq("request.attributes.region", "lie")).assert().isTrue()
        permits(Condition.Exists("http.header.x-trace"), request(host = AbsentAttributes)).assert().isFalse()
        permits(Condition.Exists("http.origin.host.labels"), request(host = AbsentAttributes)).assert().isFalse()
        AbsentAttributes.bool(Attribute.of(Attribute.Kind.SUBJECT_AUTHENTICATED)).assert().isNull()
        AbsentAttributes.stringSet(Attribute.of(Attribute.Kind.SCOPE_ROLES)).assert().isNull()
        AbsentAttributes.ip(Attribute.of(Attribute.Kind.ENVIRONMENT_IP)).assert().isNull()
        AbsentAttributes.time(Attribute.of(Attribute.Kind.ENVIRONMENT_TIME)).assert().isEqualTo(ConditionInput.NO_TIME)
    }
}
