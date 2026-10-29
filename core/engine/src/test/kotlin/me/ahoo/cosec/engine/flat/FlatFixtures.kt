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

import me.ahoo.cosec.engine.CallLog
import me.ahoo.cosec.engine.GuardBehavior
import me.ahoo.cosec.engine.MatcherException
import me.ahoo.cosec.engine.RuleBehavior
import me.ahoo.cosec.engine.request
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.AuthzRequest
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.BindingScope
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.Subject
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.CheckedCondition
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.ConditionInput
import me.ahoo.cosec.model.condition.IpAddress
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.model.scope.DeclaredScope
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.model.scope.ScopeResolution
import me.ahoo.cosec.model.scope.ScopeResolver
import me.ahoo.cosec.model.scope.ScopeSubject
import java.lang.ref.WeakReference
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

const val NOW = 1_000L

/**
 * The host attributes of test requests: `request.attributes.<key>` answers what the probe registered under `<key>`
 * answers, logging the read. Probes stand in for matchers with a given behavior, so tests can still count and order
 * what an evaluation evaluated.
 */
object Probes : ConditionInput {
    class Probe(val log: CallLog, val call: String, val answer: () -> String?)

    private val named = ConcurrentHashMap<String, Probe>()

    // A fixture's probes, by its prefix; held weakly so that random cases do not pile up for the whole test run.
    private val fixtures = ConcurrentHashMap<String, WeakReference<Map<String, Probe>>>()

    /** A probe for tests that build their own conditions. */
    fun register(key: String, probe: Probe) {
        named[key] = probe
    }

    /** The probes of a fixture, keyed `<prefix>/...`. */
    fun register(prefix: String, probes: Map<String, Probe>) {
        fixtures.values.removeIf { it.get() == null }
        fixtures[prefix] = WeakReference(probes)
    }

    private fun probe(key: String): Probe? = named[key] ?: fixtures[key.substringBefore('/')]?.get()?.get(key)

    override fun string(attribute: Attribute): String? {
        if (attribute.kind != Attribute.Kind.REQUEST_ATTRIBUTE) {
            return null
        }
        val probe = probe(attribute.name) ?: return null
        probe.log.calls += probe.call
        return probe.answer()
    }

    override fun bool(attribute: Attribute): Boolean? = null

    override fun stringSet(attribute: Attribute): Set<String>? = null

    override fun ip(attribute: Attribute): IpAddress? = null

    override fun time(attribute: Attribute): Long = ConditionInput.NO_TIME
}

/** A policy condition that behaves as [behavior] and logs `guard:<name>`. */
class TestCondition(
    val name: String,
    val behavior: GuardBehavior,
    val error: MatcherException,
    val checked: CheckedCondition
)

/** What a fixture policy was built from, for the oracle. */
class PolicySpec(val statements: List<Pair<Effect, RuleBehavior>>, val guard: TestCondition?)

/**
 * Builds compiled policies whose statements behave as given and log to [log]: `guard:<name>` for a policy condition,
 * `rule:<policy id>.<index>` for a statement's condition. A statement that misses its action never logs: it is not a
 * candidate. Every statement is named `<policy id>.<index>`.
 */
class FlatFixture(val log: CallLog = CallLog()) {
    private val prefix = "f${fixtures.incrementAndGet()}"
    private val probes = HashMap<String, Probes.Probe>().also { Probes.register(prefix, it) }
    private var probeCount = 0
    private val specs = IdentityHashMap<CompiledPolicy, PolicySpec>()
    private val errors = IdentityHashMap<CompiledPolicy, Map<String, MatcherException>>()

    private fun probe(key: String, call: String, answer: () -> String?): Condition {
        // Two documents may define policies of the same id: each probe has its own key.
        val name = "$prefix/${probeCount++}/$key"
        probes[name] = Probes.Probe(log, call, answer)
        return Condition.Eq("request.attributes.$name", Literal.Str(YES))
    }

    fun guard(name: String, behavior: GuardBehavior = GuardBehavior.MATCH): TestCondition {
        val error = MatcherException("guard $name")
        val condition = probe("guard:$name", "guard:$name") {
            when (behavior) {
                GuardBehavior.MATCH -> YES
                GuardBehavior.MISS -> NO
                GuardBehavior.THROW -> throw error
            }
        }
        return TestCondition(name, behavior, error, compiler.condition(PolicyId(name), condition))
    }

    private fun statement(
        name: String,
        effect: Effect,
        behavior: RuleBehavior,
        errors: MutableMap<String, MatcherException>,
    ): StatementSource {
        val error = MatcherException("rule $name")
        errors[name] = error
        val condition = probe("rule:$name", "rule:$name") {
            when (behavior) {
                RuleBehavior.MATCH, RuleBehavior.ACTION_MISS -> YES
                RuleBehavior.CONDITION_MISS -> NO
                RuleBehavior.THROW -> throw error
            }
        }
        val action = if (behavior == RuleBehavior.ACTION_MISS) "none:none" else "*"
        return StatementSource(name, effect, listOf(action), condition = condition)
    }

    fun policy(
        id: String,
        vararg statements: Pair<Effect, RuleBehavior>,
        guard: TestCondition? = null,
        attachable: Boolean = false,
        options: IndexOptions = IndexOptions.DEFAULT,
    ): CompiledPolicy {
        val policyErrors = HashMap<String, MatcherException>()
        val sources = statements.mapIndexed { index, (effect, behavior) ->
            statement("$id.$index", effect, behavior, policyErrors)
        }
        val compiled = PolicyCompiler(options = options).compile(PolicyId(id), null, sources, attachable)
        // Built again around the shared guard instance, so policies that share it evaluate it once.
        val policy = CompiledPolicy(PolicyId(id), guard?.checked, compiled.statements, attachable, options)
        specs[policy] = PolicySpec(statements.toList(), guard)
        errors[policy] = policyErrors
        return policy
    }

    /** The error statement [name] of [policy] throws. */
    fun error(policy: CompiledPolicy, name: String): MatcherException = errors.getValue(policy).getValue(name)

    /** What [policy] was built from; null for a policy this fixture did not build. */
    fun spec(policy: CompiledPolicy): PolicySpec? = specs[policy]

    private companion object {
        const val YES = "yes"
        const val NO = "no"
        val fixtures = AtomicInteger()
        val compiler = PolicyCompiler()
    }
}

fun allow(behavior: RuleBehavior = RuleBehavior.MATCH) = Effect.ALLOW to behavior

fun deny(behavior: RuleBehavior = RuleBehavior.MATCH) = Effect.DENY to behavior

fun anyone(id: String, policy: String, app: String? = null, space: String? = null, expiresAt: Long? = null) =
    Binding(BindingId(id), SubjectRef.Anyone, scope(app, space), PolicyId(policy), expiresAt)

fun principal(
    id: String,
    policy: String,
    tenant: String = "acme",
    principal: String = "alice",
    expiresAt: Long? = null,
    breakGlass: Boolean = false,
) = Binding(
    BindingId(id),
    SubjectRef.Principal(TenantId(tenant), PrincipalId(principal)),
    scope(null, null),
    PolicyId(policy),
    expiresAt,
    breakGlass,
)

fun root(id: String = "root", tenant: String = "(platform)", principal: String = "cosec", expiresAt: Long = NOW + 1) =
    principal(id, BuiltinPolicies.ROOT.value, tenant, principal, expiresAt, breakGlass = true)

fun role(
    id: String,
    role: String,
    policy: String,
    app: String? = "app",
    space: String? = "",
    tenant: String? = null,
    expiresAt: Long? = null,
) = Binding(
    BindingId(id),
    SubjectRef.Role(tenant?.let(::TenantId), RoleId(role)),
    scope(app, space),
    PolicyId(policy),
    expiresAt
)

private fun scope(app: String?, space: String?) =
    BindingScope(
        app?.let { Match.Exactly(AppId(it)) } ?: Match.Any,
        space?.let { Match.Exactly(SpaceId(it)) } ?: Match.Any
    )

fun platform(vararg policies: CompiledPolicy, bindings: List<Binding> = emptyList()) =
    CompiledDocument.platform(policies.toList(), bindings)

fun app(vararg policies: CompiledPolicy, bindings: List<Binding> = emptyList(), app: String = "app") =
    CompiledDocument.app(AppId(app), policies.toList(), bindings)

fun tenant(vararg policies: CompiledPolicy, bindings: List<Binding> = emptyList(), tenant: String = "acme") =
    CompiledDocument.tenant(TenantId(tenant), policies.toList(), bindings)

/** A request resolved through [ScopeResolver]: [roles] are the principal's roles in [space]. */
fun flatRequest(
    principal: String = "alice",
    tenant: String = "acme",
    authenticated: Boolean = true,
    app: String = "app",
    space: String = "",
    roles: List<String> = emptyList(),
    attachments: List<String> = emptyList(),
    time: Long = NOW,
    blocked: Boolean = false,
    path: String = "/orders/1",
    principalBindings: Boolean = true,
    breakGlass: Boolean = true,
): FlatRequest {
    val memberships = Memberships(
        Memberships.Source.CLAIMS,
        if (roles.isEmpty()) emptyMap() else mapOf(SpaceId(space) to roles.mapTo(LinkedHashSet(), ::RoleId)),
        attachments.map(::PolicyId),
    )
    val resolution = ScopeResolver.resolve(
        ScopeSubject(TenantId(tenant), authenticated, memberships),
        DeclaredScope(app, space),
        HostScope(null),
    )
    val scope = (resolution as ScopeResolution.Resolved).scope
    val base = request(blocked = blocked)
    val subject =
        Subject(principal, tenant, authenticated, emptySet(), emptySet(), emptyMap(), false, principalBindings, breakGlass)
    val resource = Resource("http", path, emptyMap())
    val authz = AuthzRequest(subject, base.scope, base.action, resource, base.environment)
    return FlatRequest(authz, scope, memberships, time, Probes)
}

fun bound(id: String, document: DocumentKind = DocumentKind.PLATFORM) = BindingRef.Bound(BindingId(id), document)

fun ref(policy: String, index: Int, via: BindingRef) = StatementRef.Policy(
    PolicyId(policy),
    index,
    "$policy.$index",
    via
)

val blacklistRef = StatementRef.Policy(BuiltinPolicies.BLACKLIST, 0, "blacklist", BindingRef.Builtin)
