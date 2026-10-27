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

package me.ahoo.cosec.micrometer

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ahoo.cosec.engine.TraceLevel
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.engine.flat.FlatEngine
import me.ahoo.cosec.engine.flat.FlatEvaluation
import me.ahoo.cosec.engine.flat.FlatRequest
import me.ahoo.cosec.engine.flat.SnapshotView
import me.ahoo.cosec.model.BindingId
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.decision.BindingRef
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.StatementRef
import me.ahoo.cosec.model.scope.HostScope
import me.ahoo.cosec.runtime.Pipeline
import me.ahoo.cosec.runtime.authn.Authenticator
import me.ahoo.cosec.runtime.authn.AuthnResult
import me.ahoo.cosec.runtime.event.DecisionEvent
import me.ahoo.cosec.runtime.event.DecisionListener
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.event.Delivery
import me.ahoo.cosec.runtime.http.Cookies
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.runtime.snapshot.SnapshotViews
import me.ahoo.cosec.runtime.snapshot.SuspendingSnapshotSource
import me.ahoo.cosec.runtime.snapshot.ViewKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import kotlin.time.Duration.Companion.seconds

class MetricsListenerTest {
    private val registry = SimpleMeterRegistry()

    private object Views : SnapshotViews {
        override fun peek(key: ViewKey): SnapshotView = SnapshotView(
            CompiledDocument.platform(emptyList(), emptyList()),
            null,
            key.tenant?.let { CompiledDocument.tenant(it, emptyList(), emptyList()) },
        )

        override fun load(key: ViewKey): CompletableFuture<SnapshotView> = CompletableFuture.completedFuture(peek(key))
    }

    private fun evaluate(decision: Decision, authenticator: Authenticator = Authenticator { AuthnResult.Anonymous }) {
        val metrics = MetricsListener(registry)
        val failing = object : DecisionListener {
            override val name = "failing"
            override val delivery = Delivery.REQUIRED

            override suspend fun onDecision(event: DecisionEvent) = error("down")
        }
        val listeners = DecisionListeners(listOf(metrics, failing))
        MetricsListener.bind(registry, listeners)
        val engine = object : FlatEngine {
            override fun evaluate(request: FlatRequest, snapshot: SnapshotView, trace: TraceLevel) =
                FlatEvaluation(decision, null)
        }
        val pipeline = Pipeline(authenticator, SuspendingSnapshotSource(Views), engine = engine, listeners = listeners)
        val before = recorded()
        val facts =
            HttpFacts("GET", "/orders", null, Headers.of(emptyMap()), Cookies.EMPTY, "10.0.0.1", HostScope(null))
        runBlocking {
            pipeline.evaluate(facts)
            withTimeout(5.seconds) {
                while (recorded() <= before) {
                    delay(5)
                }
            }
        }
        listeners.close()
    }

    private fun recorded(): Long = registry.find(MetricsListener.METRIC_NAME).timers().sumOf { it.count() }

    @Test
    fun recordsTheResultSourceAndCause() {
        val bound = StatementRef.Policy(PolicyId("p"), 0, "s", BindingRef.Bound(BindingId("g"), DocumentKind.PLATFORM))
        evaluate(Decision.Deny(bound))
        registry.get(MetricsListener.METRIC_NAME).tags("result", "explicit_deny", "source", "binding").timer().count()
            .assert().isEqualTo(1)
        // The failing REQUIRED listener turns a permit into 503: indeterminate, required_listener.
        evaluate(Decision.Permit(StatementRef.Policy(PolicyId("p"), 0, "s", BindingRef.Attached(PolicyId("p")))))
        registry.get(MetricsListener.METRIC_NAME)
            .tags("result", "indeterminate", "source", "attachment", "cause", "required_listener").timer().count()
            .assert().isEqualTo(1)
        evaluate(Decision.Indeterminate(Cause.MembershipUnavailable(IllegalStateException())))
        registry.get(MetricsListener.METRIC_NAME).tags("result", "indeterminate", "cause", "membership").timer().count()
            .assert().isEqualTo(1)
        evaluate(Decision.Deny(null), Authenticator { AuthnResult.Unavailable(IllegalStateException()) })
        registry.get(MetricsListener.METRIC_NAME).tags("cause", "authenticator").timer().count().assert().isEqualTo(1)
        registry.get("cosec.listener.failures").tags("listener", "failing").functionCounter().count()
            .assert().isGreaterThan(0.0)
        registry.get("cosec.audit.required.failures").functionCounter().count().assert().isGreaterThan(0.0)
    }
}
