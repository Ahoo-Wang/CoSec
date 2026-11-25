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
        private val NOT_PUBLISHED = setOf(
            "conformance",
            "architecture",
            "e2e",
            "client-host",
            "system-test",
            "system-behind",
            "system-auth",
            "system-enforcer",
            "code-coverage-report"
        )

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
            val decision = "A case of a sealed result that is complete: a new fact is a new case, not a new field (RFC 0010 Q6)."
            val value = "An id-like value that is complete: it has nothing more to say."
            listOf(
                "model.decision.RateLimitSpec" to "A rate limit rule read from a document, complete as RFC 0006 §8 states it.",
                "model.decision.BindingRef\$Attached" to decision,
                "model.decision.BindingRef\$Bound" to decision,
                "model.decision.BindingRef\$Boundary" to decision,
                "model.decision.StatementRef\$Boundary" to decision,
                "model.decision.StatementRef\$Policy" to decision,
                "model.decision.StatementRef\$ScopeRejected" to decision,
            ) + listOf(
                // The one cause that is a leaf: a document kind, nothing more to say. The others are plain classes (R2A-1).
                "NotLoaded",
            ).map { "model.decision.Cause\$$it" to decision } + listOf(
                "spi.snapshot.SnapshotKey\$App" to value,
                "spi.snapshot.SnapshotKey\$Tenant" to value,
            ) + listOf(
                // The control store's records are experimental with the store (R9, D3); MovedHead and RevisionRef are
                // experimental with the ChangeEvent that carries them (R2C-5).
                "change.ShadowSummary" to "A closed shadow window's counts by outcome, when it closed, its two " +
                    "verdicts and the instances whose evidence was missing (CTL-56): a new outcome is a new key of " +
                    "the counts, not a new field.",
            ).map { (name, reason) -> "control.$name" to reason } + listOf(
                "document.Actor" to "Who acted on a control-plane change: an id and a tenant. Delegation is a value " +
                    "of its own beside it (RFC 0009 §7), not a field.",
            )
        }.associate { (name, reason) -> "me.ahoo.cosec.$name" to reason }

        /**
         * Public classes whose signature names an internal type, each with why it may until the reason is gone. An entry
         * that no longer names one fails the test, so the list only shrinks.
         */
        val INTERNAL_EXPOSURES: Map<String, String> = run {
            val bridge = "An internal base class shared by the reactive adapters: an application names the filter, built " +
                "for it by the starter or the kit, and calls none of the base's members, which are internal."
            // Report P-1, F: the control store and its records become @InternalCoSecApi in this round; remove these then.
            val store = "Names the internal ChangeId; the control store and its records become @InternalCoSecApi in " +
                "this round (report P-1, group F)."
            listOf(
                "webflux.PipelineWebFilter" to bridge,
                "gateway.PipelineGatewayFilter" to bridge,
                "jwt.JwtTokenVerifier" to "Extends CoSecTokenVerifier, the internal mark of CoSec's own verifier, whose " +
                    "tokens alone may carry break-glass (BRK-8): an application constructs the verifier and calls " +
                    "verify, a member of the stable TokenVerifier, and can mark no verifier of its own as CoSec's.",
                "document.PolicyDocumentCompiler" to "Implements SnapshotCompiler, the internal port the snapshot " +
                    "source compiles through; its constructor and members are internal, so an application only names it.",
                "social.justauth.JustAuthProvider" to "Implements the internal SocialAuthenticationProvider; it becomes " +
                    "internal itself in this round (report P-9).",
                "control.audit.ChangeEvent" to "Its change is the internal ChangeId; the change events become stable " +
                    "with a change id an application can read in this round (report P-2, group G).",
                "control.store.ControlStore" to store,
                "control.store.InMemoryControlStore" to store,
                "control.store.ListedChange" to store,
                "control.store.ListedChange\$Readable" to store,
                "control.store.ListedChange\$Unreadable" to store,
                "control.store.EndedChange" to store,
                "control.change.ChangeRequest" to store,
                "control.change.StageSlot" to store,
                "cocache.control.CoCacheControlStore" to store,
            ).associate { (name, reason) -> "me.ahoo.cosec.$name" to reason }
        }

        /**
         * Stable classes whose signature names an experimental type, each with why it may. An entry that no longer
         * names one fails the test.
         */
        val EXPERIMENTAL_EXPOSURES: Map<String, String> = run {
            val store = "Implements SnapshotDocumentStore, experimental in 8.0 until its shape is settled in 8.1 (group " +
                "C): the class itself is a value of the store's stable kind, and a host that passes it as the store " +
                "opts in where it names the interface (Pipeline.builder)."
            listOf(
                "spi.snapshot.InMemorySnapshotDocumentStore" to store,
                "cocache.snapshot.RedisSnapshotDocumentStore" to store,
            ).associate { (name, reason) -> "me.ahoo.cosec.$name" to reason }
        }
    }

    /**
     * No public signature, stable or experimental, names an internal type, its supertypes included: an application never
     * needs the internal opt-in, to call a type or to implement it. An experimental type is one an application uses
     * today, with an opt-in it may give; an internal type in its signature is one it cannot (how `ControlStore` and
     * `ChangeEvent` came to expose `ChangeId`, report P-1, P-2). Only [INTERNAL_EXPOSURES] are excused, each with why.
     */
    @Test
    fun `no public signature names an internal type`() {
        val public = PublicSignatures.classes(::published)
        public.map { it.name }.assert().contains("me.ahoo.cosec.runtime.Pipeline")
        val internal = names(internalClasses)
        val violations = exposures(public, internal, stableOnly = false)
        violations.filterKeys { it !in INTERNAL_EXPOSURES }.values.flatten().assert()
            .describedAs("public signatures naming @InternalCoSecApi types; mark the member, or promote the type")
            .isEmpty()
        INTERNAL_EXPOSURES.keys.filterNot { it in violations }.assert()
            .describedAs("INTERNAL_EXPOSURES entries that name no internal type any more: remove them")
            .isEmpty()
    }

    /**
     * What is stable promises its whole signature for 8.x, so it names nothing that may change in a minor release, its
     * supertypes included: a member that needs an experimental type is experimental itself, and a stable class does not
     * implement an experimental interface. The compiler does not say so once a file opts in. Only
     * [EXPERIMENTAL_EXPOSURES] are excused, each with why.
     */
    @Test
    fun `no stable public signature names an experimental type`() {
        val all = PublicSignatures.classes(::published)
        val experimental = all.filter(::isExperimental).map { it.name }.toSet()
        all.map { it.name }.assert().contains("me.ahoo.cosec.testkit.PipelineFixture")
        val violations = exposures(all.filterNot(::isExperimental), names(experimental), stableOnly = true)
        violations.filterKeys { it !in EXPERIMENTAL_EXPOSURES }.values.flatten().assert()
            .describedAs("stable signatures naming @ExperimentalCoSecApi types; mark the member, or promote the type")
            .isEmpty()
        EXPERIMENTAL_EXPOSURES.keys.filterNot { it in violations }.assert()
            .describedAs("EXPERIMENTAL_EXPOSURES entries that name no experimental type any more: remove them")
            .isEmpty()
    }

    /** A pattern that matches any of [classes] as a whole type name in a signature. */
    private fun names(classes: Set<String>): Regex =
        Regex(classes.joinToString("|", """(?<![\w.$])(?:""", """)(?![\w$])""") { Regex.escape(it) })

    /** The signatures of each of [classes] that name a type [type] matches, supertypes included, by class. */
    private fun exposures(classes: List<JavaClass>, type: Regex, stableOnly: Boolean): Map<String, List<String>> =
        classes.associate { it.name to PublicSignatures.violations(listOf(it), type, stableOnly) }
            .filterValues { it.isNotEmpty() }

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
