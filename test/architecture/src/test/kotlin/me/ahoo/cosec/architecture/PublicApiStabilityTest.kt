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

package me.ahoo.cosec.architecture

import com.tngtech.archunit.core.domain.JavaClass
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * RFC 0010 C4: every public declaration of a published module is stable, `@ExperimentalCoSecApi`, or kept from
 * applications (`internal`, `@InternalCoSecApi`). What is stable names nothing internal, so that no application ever
 * needs the internal opt-in, and a public `data class` is a reviewed value: adding a field to one changes `copy`, so
 * [REVIEWED_VALUES] lists only what will not grow, with why.
 */
class PublicApiStabilityTest {
    companion object {
        private const val INTERNAL_API = "me.ahoo.cosec.annotations.InternalCoSecApi"
        private const val EXPERIMENTAL_API = "me.ahoo.cosec.annotations.ExperimentalCoSecApi"
        private val PUBLISHED_GROUPS = setOf("api", "core", "backends", "edge", "extensions", "host", "test")
        private val NOT_PUBLISHED = setOf("conformance", "architecture", "e2e", "code-coverage-report")

        /** Whether [project] is a library module applications compile against. */
        fun published(project: String): Boolean =
            Projects.groupOf(project) in PUBLISHED_GROUPS && project !in NOT_PUBLISHED

        private val internalClasses: Set<String> by lazy {
            SourceRulesTest.CLASSES
                .filter { javaClass -> SourceRulesTest.projectOf(javaClass)?.let(::published) == true }
                .filter(::isInternalApi)
                .map { it.name }
                .toSet()
        }

        private fun isInternalApi(javaClass: JavaClass): Boolean {
            if (javaClass.isAnnotatedWith(INTERNAL_API)) return true
            return javaClass.enclosingClass.orElse(null)?.let(::isInternalApi) == true
        }

        private fun isExperimental(javaClass: JavaClass): Boolean {
            if (javaClass.isAnnotatedWith(EXPERIMENTAL_API)) return true
            return javaClass.enclosingClass.orElse(null)?.let(::isExperimental) == true
        }

        /**
         * Public `data class`es that will not grow a field (RFC 0010 §3 C4). A record that can grow is a plain class
         * instead (a new field is a secondary constructor), and what applications do not need is internal. Keep one
         * here only with a reason it cannot grow: a new fact belongs in a new value, as `Cause` and `StatementRef` are.
         */
        val REVIEWED_VALUES: Map<String, String> = run {
            val decision = "A case of a sealed decision result: a new fact is a new case, not a new field (RFC 0010 Q6)."
            val value = "An id-like value that is complete: it has nothing more to say."
            val storeRecord = "A record of the control store or its change events: an application reads it or stores it " +
                "whole (ChangeRequestCodec), and only cosec-control constructs or copies it, so a new field breaks no caller."
            listOf(
                "model.condition.Literal\$Bool" to "A literal value.",
                "model.condition.Literal\$Str" to "A literal value.",
                "model.decision.RateLimitSpec" to "A rate limit rule read from a document, complete as RFC 0006 §8 states it.",
                "model.decision.Obligation\$RateLimit" to decision,
                "model.decision.BindingRef\$Attached" to decision,
                "model.decision.BindingRef\$Bound" to decision,
                "model.decision.BindingRef\$Boundary" to decision,
                "model.decision.StatementRef\$Boundary" to decision,
                "model.decision.StatementRef\$Policy" to decision,
                "model.decision.StatementRef\$ScopeRejected" to decision,
                "model.decision.Decision\$Permit" to decision,
                "model.decision.Decision\$Deny" to decision,
                "model.decision.Decision\$Indeterminate" to decision,
            ) + listOf(
                "AmbiguousPolicy", "AttributeUnavailable", "BoundaryUnavailable", "MatcherFailed", "MembershipUnavailable",
                "MissingPolicies", "NotLoaded", "RateLimiterUnavailable", "RequiredListenerFailed", "SourceUnavailable",
            ).map { "model.decision.Cause\$$it" to decision } + listOf(
                "engine.flat.ViewVersion" to value,
                "spi.ratelimit.RateLimit" to value,
                "spi.ratelimit.RateLimitResult" to value,
                "spi.snapshot.SnapshotKey\$App" to value,
                "spi.snapshot.SnapshotKey\$Tenant" to value,
                "runtime.token.SimpleCompositeToken" to "An access token and a refresh token: the pair is the whole of a composite token.",
            ) + listOf(
                "audit.MovedHead", "audit.RevisionRef", "change.Approval", "change.Base", "change.ChangeRequest",
                "change.CheckedApps", "change.Confirmation", "change.Escalation", "change.Finding", "change.ShadowState",
                "change.ShadowSummary", "change.StageSlot", "store.AppList", "store.PendingRecord", "store.RevisionEntry",
                "store.RevisionIndex",
            ).map { "control.$it" to storeRecord } + listOf(
                "runtime.document.Actor" to "Who acted on a control-plane change, as its record names them: an id and a tenant.",
            )
        }.associate { (name, reason) -> "me.ahoo.cosec.$name" to reason }
    }

    @Test
    fun `no stable public signature names an internal type`() {
        val stable = PublicSignatures.classes(::published)
        stable.map { it.name }.assert().contains("me.ahoo.cosec.runtime.Pipeline")
        val internal =
            Regex(internalClasses.joinToString("|", """(?<![\w.$])(?:""", """)(?![\w$])""") { Regex.escape(it) })
        // A class may extend an internal base class: its users name only the class, and the compiler asks them for no
        // opt-in. Its own public members are what they call, and those are checked.
        val violations = PublicSignatures.violations(stable, internal).filterNot { " extends " in it }
        violations.assert()
            .describedAs("stable signatures naming @InternalCoSecApi types; mark the member, or promote the type")
            .isEmpty()
    }

    @Test
    fun `a public data class of a published module is a reviewed value`() {
        val dataClasses = PublicSignatures.classes(::published)
            .filter { it.reflect().kotlin.isData && it.reflect().kotlin.objectInstance == null }
            .filterNot(::isExperimental)
            .map { it.name }
        dataClasses.assert().contains("me.ahoo.cosec.model.decision.RateLimitSpec")
        dataClasses.filterNot { it in REVIEWED_VALUES }.assert()
            .describedAs("a public data class not in REVIEWED_VALUES: make it a plain class, or internal")
            .isEmpty()
        REVIEWED_VALUES.keys.filterNot { it in dataClasses }.assert()
            .describedAs("REVIEWED_VALUES entries that are no longer public data classes")
            .isEmpty()
    }
}
