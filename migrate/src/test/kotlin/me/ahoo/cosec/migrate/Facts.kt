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

package me.ahoo.cosec.migrate

import me.ahoo.cosec.api.configuration.Configuration
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.api.context.request.AppIdCapable
import me.ahoo.cosec.api.context.request.DeviceIdCapable
import me.ahoo.cosec.api.context.request.SpaceIdCapable
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.context.SimpleSecurityContext
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.CheckResult
import me.ahoo.cosec.model.condition.ConditionChecker
import me.ahoo.cosec.model.condition.ConditionInput
import me.ahoo.cosec.model.condition.IpAddress
import me.ahoo.cosec.policy.EvaluateRequest
import me.ahoo.cosec.policy.action.setPathVariables
import me.ahoo.cosec.policy.condition.ConditionMatcherFactory
import me.ahoo.cosec.policy.condition.ConditionMatcherFactoryProvider
import me.ahoo.cosec.policy.condition.TimeConditionMatcher
import me.ahoo.cosec.policy.condition.TimeConditionMatcherFactory
import me.ahoo.cosec.principal.SimplePrincipal
import me.ahoo.cosec.principal.SimpleTenantPrincipal
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.snapshot.document.JavaZones
import me.ahoo.cosec.tenant.SimpleTenant
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.random.Random

/**
 * The same request seen by 7.x (a `Request` and a `SecurityContext`) and by 8.0 (the attributes of RFC 0005 §3.2,
 * filled the way `FlatEvaluator` fills them: no subject id for an anonymous subject, no app or device when empty, the
 * roles of the requested space as `scope.roles`). Headers, origin and referer are the host's attributes (RFC 0006
 * PR-4): a header absent from the request is absent, a host is `URI.getHost`.
 */
data class Facts(
    val path: String,
    val method: String,
    val appId: String,
    val spaceId: String,
    val deviceId: String,
    val origin: String?,
    val referer: String?,
    val headers: Map<String, String>,
    val requestAttributes: Map<String, String>,
    val resourceAttributes: Map<String, String>,
    val pathVariables: Map<String, String>,
    val authenticated: Boolean,
    val principalId: String,
    val tenantId: String,
    /** As a 7.x token claims them: `role` in the default space, `role@space` elsewhere. */
    val roles: Set<String>,
    val principalAttributes: Map<String, String>,
    val time: Instant,
) {
    /** 7.x's view. */
    fun request(): Pair<EvaluateRequest, SecurityContext> {
        val headers = this.headers + mapOf(
            AppIdCapable.APP_ID_KEY to appId,
            SpaceIdCapable.SPACE_ID_KEY to spaceId,
            DeviceIdCapable.DEVICE_ID_KEY to deviceId,
        )
        val request = EvaluateRequest(
            path = path,
            method = method,
            origin = URI.create(origin.orEmpty()),
            referer = URI.create(referer.orEmpty()),
            attributes = requestAttributes + resourceAttributes.mapKeys { "resource.${it.key}" },
            headers = headers,
        )
        val principal = if (authenticated) {
            SimpleTenantPrincipal(
                SimplePrincipal(principalId, emptySet(), roles, principalAttributes),
                SimpleTenant(tenantId)
            )
        } else {
            SimpleTenantPrincipal.ANONYMOUS
        }
        val context = SimpleSecurityContext(principal)
        context.setPathVariables(pathVariables)
        return request to context
    }

    /** 8.0's view. */
    val input: ConditionInput = object : ConditionInput {
        override fun string(attribute: Attribute): String? = when (attribute.path) {
            "subject.id" -> principalId.takeIf { authenticated }
            "subject.tenant", "scope.tenant" -> if (authenticated) tenantId else "(0)"
            "scope.app" -> appId.ifEmpty { null }
            "scope.space" -> spaceId
            "environment.device" -> deviceId.ifEmpty { null }
            "action.verb" -> method
            "resource.id" -> path
            "http.origin" -> origin
            "http.referer" -> referer
            "http.origin.host" -> origin?.let { URI.create(it).host }
            "http.referer.host" -> referer?.let { URI.create(it).host }
            else -> when (attribute.kind) {
                Attribute.Kind.SUBJECT_ATTRIBUTE -> if (authenticated) principalAttributes[attribute.name] else null
                Attribute.Kind.RESOURCE_ATTRIBUTE -> resourceAttributes[attribute.name]
                Attribute.Kind.RESOURCE_VAR -> pathVariables[attribute.name]
                Attribute.Kind.HTTP_HEADER -> headers[attribute.name]
                Attribute.Kind.REQUEST_ATTRIBUTE -> requestAttributes[attribute.name]
                else -> null
            }
        }

        override fun bool(attribute: Attribute): Boolean? = if (attribute.path == "subject.authenticated") authenticated else null

        override fun stringSet(attribute: Attribute): Set<String>? {
            val claims = if (authenticated) roles else emptySet()
            return when (attribute.path) {
                "subject.roles" -> claims.mapTo(HashSet()) { it.substringBefore('@') }
                "subject.scopedRoles" -> claims
                "scope.roles" -> claims.filter { (if ('@' in it) it.substringAfter('@') else "") == spaceId }
                    .mapTo(HashSet()) { it.substringBefore('@') }

                else -> null
            }
        }

        override fun ip(attribute: Attribute): IpAddress? = null

        override fun time(attribute: Attribute): Long =
            if (attribute.path == "environment.time") time.toEpochMilli() else ConditionInput.NO_TIME
    }

    companion object {
        private val STRINGS = listOf(
            "", "a", "A", "admin", "Admin", "ADMIN", "adm", "admin2", "(0)", "(platform)", "acme", "x", "é", "É",
            "K", "k", "K", "ſ", "s", "S", "İ", "i", "I", "/orders/1", "/Orders/1", "/orders", "/orders/1/items",
            "line\nbreak", "admin\n", "αβγ", "a.b", "a|b", "b", "GET", "get", "team", "owner-1",
        )
        private val ORIGINS = listOf(
            null, "https://example.com", "https://api.example.com", "https://API.Example.COM", "https://badexample.com",
            "https://example.com.evil.org", "null", "https://a.b.example.com:8443/x", "http://localhost",
        )
        private val ROLES = listOf("admin", "viewer", "admin@team", "viewer@team", "admin@other", "Key")

        /** Times around the boundaries the time corpus uses, and a few anywhere. */
        val TIMES: List<Instant> = listOf(
            "2026-03-29T00:59:59Z", "2026-03-29T01:00:00Z", "2026-03-29T01:59:59Z", "2026-03-29T02:00:00Z",
            "2026-10-25T00:59:59Z", "2026-10-25T01:00:00Z", "2026-10-25T02:00:00Z", "2026-10-26T07:59:59Z",
            "2026-10-26T08:00:00Z", "2026-10-26T16:59:59Z", "2026-10-26T17:00:00Z", "2026-10-26T22:59:59Z",
            "2026-10-26T23:00:00Z", "2026-10-27T00:00:00Z", "2026-10-27T05:59:59Z", "2026-10-27T06:00:00Z",
            "2026-10-31T23:59:59Z", "2026-11-01T00:00:00Z", "2027-01-01T00:00:00Z", "2025-12-31T23:59:59.999Z",
        ).map(Instant::parse)

        fun random(random: Random): Facts {
            fun string() = STRINGS.random(random)
            fun map(keys: List<String>) = keys.filter { random.nextBoolean() }.associateWith { string() }
            val authenticated = random.nextInt(4) != 0
            return Facts(
                path = STRINGS.filter { it.startsWith("/") }.random(random),
                method = listOf("GET", "POST", "get").random(random),
                appId = listOf("", "order", "Order").random(random),
                spaceId = listOf("", "team", "other").random(random),
                deviceId = listOf("", "d1").random(random),
                origin = ORIGINS.random(random),
                referer = ORIGINS.random(random),
                headers = map(listOf("x-tenant", "x-debug")),
                requestAttributes = map(listOf("owner", "region")),
                resourceAttributes = map(listOf("owner")),
                pathVariables = map(listOf("id")),
                authenticated = authenticated,
                principalId = if (authenticated) listOf("alice", "owner-1", "(0)x", "a").random(random) else "(0)",
                tenantId = listOf("(0)", "(platform)", "acme").random(random),
                roles = ROLES.filter { random.nextInt(3) == 0 }.toSet(),
                principalAttributes = map(listOf("owner", "dept")),
                time = if (random.nextBoolean()) {
                    TIMES.random(
                        random
                    )
                } else {
                    Instant.ofEpochSecond(random.nextLong(1_700_000_000, 1_900_000_000))
                },
            )
        }
    }
}

/** Evaluates 7.x conditions at a chosen time: `time` conditions are created on [clock] while [use] runs. */
internal object Matchers7x {
    class MutableClock : Clock() {
        @Volatile
        var now: Instant = Instant.EPOCH

        override fun instant(): Instant = now

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this
    }

    val clock = MutableClock()

    /** `TimeConditionMatcher`'s clock constructor is internal to cosec-core; the test reaches it by reflection. */
    private object ClockedTime : ConditionMatcherFactory {
        private val constructor = TimeConditionMatcher::class.java.getDeclaredConstructor(
            Configuration::class.java,
            Clock::class.java
        )
            .also { it.isAccessible = true }
        override val type: String = TimeConditionMatcherFactory.TYPE

        override fun create(configuration: Configuration): ConditionMatcher = constructor.newInstance(
            configuration,
            clock
        )
    }

    fun <T> use(block: () -> T): T {
        val previous = ConditionMatcherFactoryProvider.get(TimeConditionMatcherFactory.TYPE)
        ConditionMatcherFactoryProvider.register(ClockedTime)
        try {
            return block()
        } finally {
            ConditionMatcherFactoryProvider.register(previous ?: TimeConditionMatcherFactory())
        }
    }

    fun matcher(json: String): ConditionMatcher = CoSecJsonSerializer.readValue(json, ConditionMatcher::class.java)

    fun matches(matcher: ConditionMatcher, facts: Facts): Boolean {
        clock.now = facts.time
        val (request, context) = facts.request()
        return matcher.match(request, context)
    }

    /** 8.0's verdict on [translated], checked as the policy compiler checks it. */
    fun evaluator(translated: Translated): (Facts) -> Boolean = when (translated) {
        Translated.Always -> { _ -> true }
        Translated.Never -> { _ -> false }
        is Translated.Ast -> {
            val checked = when (val result = ConditionChecker(zones = JavaZones.RESOLVER).check(translated.condition)) {
                is CheckResult.Valid -> result.condition
                is CheckResult.Invalid -> error("The translation does not compile: ${result.errors}")
            }
            { facts -> checked.evaluate(facts.input) }
        }
    }
}
