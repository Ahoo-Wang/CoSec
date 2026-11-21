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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.runtime.event

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.runtime.PipelineFixtures
import me.ahoo.cosec.runtime.PipelineFixtures.StubEngine
import me.ahoo.cosec.runtime.PipelineFixtures.authenticated
import me.ahoo.cosec.runtime.PipelineFixtures.authenticator
import me.ahoo.cosec.runtime.PipelineFixtures.facts
import me.ahoo.cosec.runtime.audit.AuditEvents
import me.ahoo.cosec.runtime.shadow.sampledBy
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.io.File

/**
 * OBS-9: a cause is named on the wire by one table, [CauseName], never by its class: the audit record's reason and
 * the shadow report's sample carry the names `vocabulary/causes.json` pins, whatever the classes are called.
 */
class CauseNameTest {
    private val error = IllegalStateException("x")
    private val at = StatementRef.Policy(PolicyId("p"), 0, "s", BindingRef.Builtin)

    /** One of each kind of cause, in the order of the pinned names. */
    private val samples: List<Cause> = listOf(
        Cause.MatcherFailed(at, false, error),
        Cause.MissingPolicies(setOf(PolicyId("p"))),
        Cause.AmbiguousPolicy(setOf(PolicyId("p"))),
        Cause.SourceUnavailable(DocumentKind.APP, error),
        Cause.DocumentInvalid(DocumentKind.APP, 1, error),
        Cause.NotLoaded(DocumentKind.TENANT),
        Cause.BoundaryUnavailable(PolicyId("fence"), error),
        Cause.AttributeUnavailable(setOf("ext.geo.inFence"), null),
        Cause.CheckFailed(error),
        Cause.MembershipUnavailable(error),
        Cause.RequiredListenerFailed(listOf("audit")),
        Cause.RateLimiterUnavailable("platform/strict"),
    )

    private val pinned: List<String> = javaClass.getResourceAsStream("/vocabulary/causes.json").use {
        CoSecJson.mapper.readTree(it).get("causes").values().map { name -> name.asString() }
    }

    private class Capture : DecisionListener {
        override val name = "capture"
        override val delivery = Delivery.REQUIRED
        var event: DecisionEvent? = null

        override suspend fun onDecision(event: DecisionEvent) {
            this.event = event
        }
    }

    private fun auditReason(cause: Cause): String {
        val capture = Capture()
        val listeners = DecisionListeners(listOf(capture))
        try {
            runBlocking {
                PipelineFixtures.pipeline(authenticator(authenticated()), StubEngine(Decision.Indeterminate(cause))) {
                    listeners(listeners)
                }.evaluate(facts("/orders"))
            }
        } finally {
            listeners.cancel()
        }
        return AuditEvents.from(capture.event!!).authorization.reason
    }

    @Test
    fun `the table names every kind of cause, each by its pinned name`() {
        CauseName.entries.map { it.wire }.assert().isEqualTo(pinned)
        samples.map { CauseName.of(it).wire }.assert().isEqualTo(pinned)
    }

    @Test
    fun `the audit record names each cause by its pinned name`() {
        samples.zip(pinned).forEach { (cause, name) ->
            auditReason(cause).assert().describedAs(name).isEqualTo("INDETERMINATE: $name")
        }
    }

    @Test
    fun `the shadow sample names each cause by its pinned name`() {
        samples.zip(pinned).forEach { (cause, name) ->
            sampledBy(Decision.Indeterminate(cause)).assert().describedAs(name).isEqualTo(name)
        }
    }

    /**
     * Today every name is spelled as its class is, so the outputs above cannot tell a class name from the table: the
     * writers must not ask a class for its name at all, or a renamed class would change the wire with nothing failing.
     */
    @Test
    fun `the audit record and the shadow sample never name a cause by its class`() {
        val writers = listOf(
            "src/main/kotlin/me/ahoo/cosec/runtime/audit/AuditEvents.kt",
            "src/main/kotlin/me/ahoo/cosec/runtime/shadow/ShadowListener.kt",
        )
        val byClass = Regex("""::class\.(simpleName|qualifiedName|java)|javaClass""")
        writers.forEach { path ->
            byClass.find(File(path).readText())?.value.assert().describedAs(path).isNull()
        }
    }
}
