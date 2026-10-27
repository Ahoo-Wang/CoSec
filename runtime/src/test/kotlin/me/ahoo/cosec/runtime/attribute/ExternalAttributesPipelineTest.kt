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

package me.ahoo.cosec.runtime.attribute

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.engine.flat.BuiltinPolicies
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.membership.MembershipProvider
import me.ahoo.cosec.model.Memberships
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.runtime.Basis
import me.ahoo.cosec.runtime.Outcome
import me.ahoo.cosec.runtime.PipelineFixtures
import me.ahoo.cosec.runtime.PipelineFixtures.authenticated
import me.ahoo.cosec.runtime.PipelineFixtures.authenticator
import me.ahoo.cosec.runtime.PipelineOptions
import me.ahoo.cosec.runtime.Unavailability
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.document.PolicyDocumentCompiler
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

/**
 * RFC 0006 §5.4 through the pipeline, with real documents and the real engine: the blacklist in stage A, planned
 * attributes in stage B, and what an unavailable one means (`onMissing`, P9′ at the edge).
 */
class ExternalAttributesPipelineTest {
    /** `ext.geo.inFence` and `ext.geo.zone`, answered by [answer]; counts its calls. */
    private class Geo(private val answer: suspend (Set<String>) -> Map<String, Any>) : AttributeProvider {
        val calls = AtomicInteger()
        val asked = CopyOnWriteArrayList<Set<String>>()

        override val attributes = listOf(
            AttributeDeclaration(IN_FENCE, AttributeType.BOOL),
            AttributeDeclaration(ZONE, AttributeType.STRING),
        )
        override val timeout = 50.milliseconds

        override suspend fun fetch(request: AttributeRequest, names: Set<String>): Map<String, Any> {
            calls.incrementAndGet()
            asked += names
            return answer(names)
        }
    }

    /** `ext.risk.high`, from another provider. */
    private class Risk(private val answer: suspend () -> Map<String, Any>) : AttributeProvider {
        override val attributes = listOf(AttributeDeclaration(HIGH_RISK, AttributeType.BOOL))

        override suspend fun fetch(request: AttributeRequest, names: Set<String>): Map<String, Any> = answer()
    }

    /** Every document compiled once from [platform] and [tenant] bodies; records the view keys it is asked for. */
    private class Views(compiler: PolicyDocumentCompiler, platform: String) : SnapshotViews {
        val keys = CopyOnWriteArrayList<ViewKey>()
        private val platform = compiler.compile(SnapshotKey.Platform, document(platform))
        private val tenant = compiler.compile(SnapshotKey.Tenant(PipelineFixtures.TENANT.value), document(""))

        override fun peek(key: ViewKey): SnapshotView {
            keys += key
            return SnapshotView(
                platform,
                key.app?.let { CompiledDocument.app(it, emptyList(), emptyList()) },
                key.tenant?.let { tenant },
            )
        }

        override fun load(key: ViewKey): CompletableFuture<SnapshotView> = CompletableFuture.completedFuture(peek(key))

        private fun document(body: String) = SnapshotDocument(
            2,
            1,
            Instant.parse("2026-10-26T00:00:00Z"),
            """{"schema":2,"revision":1,"publishedAt":"2026-10-26T00:00:00Z"$body}""".encodeToByteArray(),
        )
    }

    private fun evaluate(
        providers: List<AttributeProvider>,
        policy: Policy,
        membershipProvider: MembershipProvider? = null,
        memberships: Memberships? = PipelineFixtures.claims(),
        headers: Map<String, List<String>> = emptyMap(),
    ) = evaluate(providers, Policies(listOf(policy)), membershipProvider, memberships, headers)

    private fun evaluate(
        providers: List<AttributeProvider>,
        policies: Policies,
        membershipProvider: MembershipProvider? = null,
        memberships: Memberships? = PipelineFixtures.claims(),
        headers: Map<String, List<String>> = emptyMap(),
    ): Pair<Outcome, Views> {
        val attributes = AttributeProviders(providers)
        val views = Views(PolicyDocumentCompiler(attributes.catalog), policies.body)
        val pipeline = PipelineFixtures.pipeline(
            authenticator = authenticator(authenticated(memberships)),
            engine = me.ahoo.cosec.engine.flat.CoSecFlatEngine,
            views = views,
        ) {
            it.copy(
                membershipProvider = membershipProvider,
                options = PipelineOptions(attributeTimeout = 50.milliseconds),
                attributes = attributes,
            )
        }
        return runBlocking { pipeline.evaluate(PipelineFixtures.facts(headers = headers)) } to views
    }

    private val Outcome.decision: Decision get() = (basis as Basis.Decided).decision

    private val Outcome.unavailableCause: Cause
        get() = ((basis as Basis.Unavailable).reason as Unavailability.Indeterminate).cause

    // --- stage A: the blacklist --------------------------------------------------------------------------------

    @Test
    fun aBlockedAddressIsDeniedByTheBuiltinRuleWithThePlatformDocumentOnly() {
        val source = InMemoryBlacklistSource().apply { block(BlacklistKind.IP, "10.0.0.1") }
        val (outcome, views) = evaluate(listOf(BlacklistAttributeProvider(source)), allow())
        outcome.status.assert().isEqualTo(403)
        (outcome.decision.let { it as Decision.Deny }.by as StatementRef.Policy).policy
            .assert().isEqualTo(BuiltinPolicies.BLACKLIST)
        views.keys.assert().containsExactly(ViewKey.BLOCKED)
    }

    @Test
    fun aBlockedPrincipalOrDeviceIsDenied() {
        val principal = InMemoryBlacklistSource().apply {
            block(BlacklistKind.PRINCIPAL, BlacklistKind.principal(PipelineFixtures.TENANT.value, "alice"))
        }
        evaluate(listOf(BlacklistAttributeProvider(principal)), allow()).first.status.assert().isEqualTo(403)
        val device = InMemoryBlacklistSource().apply { block(BlacklistKind.DEVICE, "d-1") }
        evaluate(
            listOf(BlacklistAttributeProvider(device)),
            allow(),
            headers = mapOf("CoSec-Device-Id" to listOf("d-1"))
        ).first.status.assert().isEqualTo(403)
    }

    @Test
    fun aRequestTheBlacklistDoesNotNameIsEvaluated() {
        val source = InMemoryBlacklistSource().apply {
            block(BlacklistKind.IP, "10.0.0.2")
            block(BlacklistKind.PRINCIPAL, "alice")
        }
        val (outcome, views) = evaluate(listOf(BlacklistAttributeProvider(source)), allow())
        outcome.status.assert().isEqualTo(200)
        views.keys.single().assert().isNotEqualTo(ViewKey.BLOCKED)
    }

    /** The heart of §5.4.4: a failed lookup is never "not blocked". */
    @Test
    fun aFailingBlacklistIsIndeterminateNeverNotBlocked() {
        val failing = BlacklistSource { _, _ -> error("blacklist down") }
        val (outcome, _) = evaluate(listOf(BlacklistAttributeProvider(failing)), allow())
        outcome.status.assert().isEqualTo(503)
        val cause = outcome.unavailableCause as Cause.AttributeUnavailable
        cause.attributes.assert().isEqualTo(BlacklistAttributeProvider.ATTRIBUTES.toSet())
    }

    @Test
    fun aFailingBlacklistStillLetsAMatchingDenyDecide() {
        val failing = BlacklistSource { _, _ -> error("blacklist down") }
        val (outcome, _) = evaluate(listOf(BlacklistAttributeProvider(failing)), allow() + deny())
        outcome.status.assert().isEqualTo(403)
    }

    @Test
    fun aBlacklistThatDoesNotAnswerInTimeIsIndeterminate() {
        val slow = BlacklistSource { _, _ -> awaitCancellation() }
        evaluate(listOf(BlacklistAttributeProvider(slow)), allow()).first.status.assert().isEqualTo(503)
    }

    @Test
    fun anyBlockingAnswerWinsOverAFailedOne() {
        val partly = BlacklistSource { kind, _ -> if (kind == BlacklistKind.IP) true else error("principal list down") }
        // One provider: its failure makes all its attributes unavailable, so a second provider answers the address.
        val principalOnly = object : AttributeProvider by BlacklistAttributeProvider(partly) {
            override val attributes = listOf(
                AttributeDeclaration(
                    BlacklistAttributeProvider.PRINCIPAL,
                    AttributeType.BOOL,
                    AttributeDeclaration.Stage.A
                )
            )
        }
        val ipOnly = object : AttributeProvider {
            override val attributes = listOf(
                AttributeDeclaration(BlacklistAttributeProvider.IP, AttributeType.BOOL, AttributeDeclaration.Stage.A)
            )

            override suspend fun fetch(request: AttributeRequest, names: Set<String>) =
                mapOf(BlacklistAttributeProvider.IP to true)
        }
        val (outcome, views) = evaluate(listOf(principalOnly, ipOnly), allow())
        outcome.status.assert().isEqualTo(403)
        views.keys.assert().containsExactly(ViewKey.BLOCKED)
    }

    /** K17: stage A does not wait for the memberships, nor they for it. */
    @Test
    fun stageARunsTogetherWithTheMemberships() {
        val membershipsStarted = CompletableDeferred<Unit>()
        val source = BlacklistSource { _, _ ->
            membershipsStarted.await()
            false
        }
        val provider = MembershipProvider { _, _ ->
            membershipsStarted.complete(Unit)
            Memberships(Memberships.Source.PROVIDER, emptyMap(), emptyList())
        }
        val (outcome, _) = evaluate(
            listOf(BlacklistAttributeProvider(source)),
            allow(),
            membershipProvider = provider,
            memberships = null,
        )
        outcome.status.assert().isEqualTo(200)
    }

    // --- stage B: planned attributes -------------------------------------------------------------------------

    @Test
    fun aPlannedAttributeIsFetchedAndRead() {
        val geo = Geo { mapOf(IN_FENCE to true) }
        val (outcome, _) = evaluate(listOf(geo), allow(condition = inFence()))
        outcome.status.assert().isEqualTo(200)
        geo.asked.assert().containsExactly(setOf(IN_FENCE))
    }

    @Test
    fun anAttributeOfNoCandidateIsNotFetched() {
        val geo = Geo { mapOf(IN_FENCE to true) }
        val (outcome, _) = evaluate(listOf(geo), allow() + allow("p2", action = "order:*", condition = inFence()))
        outcome.status.assert().isEqualTo(200)
        geo.calls.get().assert().isZero()
    }

    @Test
    fun anAbsentValueIsNoMatchNotUnavailable() {
        val (outcome, _) = evaluate(listOf(Geo { emptyMap() }), allow(condition = inFence()))
        outcome.status.assert().isEqualTo(403)
        outcome.decision.assert().isEqualTo(Decision.Deny(null))
    }

    /** K12: an ALLOW skipped for an unavailable attribute makes the implicit deny a 503. */
    @Test
    fun anUnavailableAllowIsIndeterminateInsteadOfImplicitDeny() {
        val (outcome, _) = evaluate(listOf(Geo { error("geo down") }), allow(condition = inFence()))
        outcome.status.assert().isEqualTo(503)
        (outcome.unavailableCause as Cause.AttributeUnavailable).attributes.assert().containsExactly(IN_FENCE)
    }

    @Test
    fun anUnavailableAllowWithNomatchIsAMiss() {
        val (outcome, _) = evaluate(
            listOf(Geo { error("geo down") }),
            allow(condition = inFence(), onMissing = "nomatch")
        )
        outcome.status.assert().isEqualTo(403)
    }

    @Test
    fun anotherAllowStillPermits() {
        val (outcome, _) = evaluate(listOf(Geo { error("geo down") }), allow(condition = inFence()) + allow("p2"))
        outcome.status.assert().isEqualTo(200)
    }

    @Test
    fun anUnavailableDenyIsIndeterminateAndNeverPermits() {
        val (outcome, _) = evaluate(listOf(Geo { error("geo down") }), allow() + deny(condition = inFence()))
        outcome.status.assert().isEqualTo(503)
    }

    @Test
    fun aDenyWithNomatchLapsesInThePlatformDocument() {
        val (outcome, _) = evaluate(
            listOf(Geo { error("geo down") }),
            allow() + deny(condition = inFence(), onMissing = "nomatch")
        )
        outcome.status.assert().isEqualTo(200)
    }

    @Test
    fun aTimeoutAndAWrongTypeAreUnavailable() {
        evaluate(listOf(Geo { awaitCancellation() }), allow(condition = inFence())).first.status.assert().isEqualTo(503)
        evaluate(listOf(Geo { mapOf(IN_FENCE to "yes") }), allow(condition = inFence())).first.status
            .assert().isEqualTo(503)
    }

    /** A provider that fails makes only its own attributes unavailable: the other's DENY still matches (RFC 0004 Q6). */
    @Test
    fun aFailingProviderDoesNotCancelTheOthers() {
        val (outcome, _) = evaluate(
            listOf(Geo { error("geo down") }, Risk { mapOf(HIGH_RISK to true) }),
            allow(condition = inFence()) + deny(condition = """{"eq":["$HIGH_RISK",true]}"""),
        )
        outcome.status.assert().isEqualTo(403)
        outcome.decision.assert().isInstanceOf(Decision.Deny::class.java)
        (outcome.decision as Decision.Deny).by.assert().isNotNull()
    }

    @Test
    fun aPolicyCanReadTheBlacklistAttributesToo() {
        val source = InMemoryBlacklistSource()
        val (outcome, _) = evaluate(
            listOf(BlacklistAttributeProvider(source)),
            allow(condition = """{"eq":["${BlacklistAttributeProvider.IP}",false]}"""),
        )
        outcome.status.assert().isEqualTo(200)
    }

    // --- the registry ------------------------------------------------------------------------------------------

    @Test
    fun theRegistryRefusesWhatItCannotFetch() {
        assertThrows<IllegalArgumentException> { AttributeProviders(listOf(Geo { emptyMap() }, Geo { emptyMap() })) }
        val stageBBlacklist = object : AttributeProvider {
            override val attributes = listOf(AttributeDeclaration(BlacklistAttributeProvider.IP, AttributeType.BOOL))

            override suspend fun fetch(request: AttributeRequest, names: Set<String>) = emptyMap<String, Any>()
        }
        assertThrows<IllegalArgumentException> { AttributeProviders(listOf(stageBBlacklist)) }
        val badPath = object : AttributeProvider {
            override val attributes = listOf(AttributeDeclaration("geo.inFence", AttributeType.BOOL))

            override suspend fun fetch(request: AttributeRequest, names: Set<String>) = emptyMap<String, Any>()
        }
        assertThrows<IllegalArgumentException> { AttributeProviders(listOf(badPath)) }
        val registered = AttributeProviders(listOf(Geo { emptyMap() }))
        registered.declares(IN_FENCE).assert().isTrue()
        registered.declares(HIGH_RISK).assert().isFalse()
        registered.catalog.external(ZONE)!!.type.assert().isEqualTo(AttributeType.STRING)
        AttributeProviders.NONE.isEmpty.assert().isTrue()
    }

    private companion object {
        const val IN_FENCE = "ext.geo.inFence"
        const val ZONE = "ext.geo.zone"
        const val HIGH_RISK = "ext.risk.high"

        fun inFence() = """{"eq":["$IN_FENCE",true]}"""

        fun allow(id: String = "p", action: String = "*", condition: String? = null, onMissing: String? = null) =
            Policy(id, "allow", action, condition, onMissing)

        fun deny(condition: String? = null, onMissing: String? = null) = Policy("d", "deny", "*", condition, onMissing)
    }

    /** One policy of one statement, bound to anyone in the platform document. */
    private class Policy(val id: String, effect: String, action: String, condition: String?, onMissing: String?) {
        val json = """{"id":"$id","statements":[{"effect":"$effect","action":"$action"""" +
            (condition?.let { ""","condition":$it""" } ?: "") +
            (onMissing?.let { ""","onMissing":"$it"""" } ?: "") + "}]}"

        operator fun plus(other: Policy): Policies = Policies(listOf(this, other))
    }

    private class Policies(val policies: List<Policy>) {
        val body: String
            get() = ""","policies":[${policies.joinToString(",") { it.json }}],"bindings":[""" +
                policies.joinToString(",") {
                    """{"id":"b-${it.id}","subject":"*","app":"*","space":"*","policy":"${it.id}"}"""
                } + "]"
    }
}
