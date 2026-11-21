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

package me.ahoo.cosec.system

import java.time.Duration

/**
 * How long a scenario may wait for the system to converge, and why that long: each bound is a documented property of
 * CoSec, named with the rule it comes from, so a failure says which promise the system did not keep in time. A bound is
 * a deadline for [eventually], never a pause.
 */
class Bound(val name: String, val duration: Duration, val source: String) {
    override fun toString(): String = "$name [${duration.toMillis()} ms: $source]"
}

object Bounds {
    /**
     * A document written to Redis is installed by every instance: at once through CoCache's invalidation message, and at
     * the latest by the next revalidation pass, every `cosec.authorization.snapshot.revalidate-interval` (10 s, SNP-6),
     * plus a cold load (2 s: 40 % of `cosec.pipeline.timeout`, 5 s) and 3 s for the requests that observe it.
     */
    val SNAPSHOT_PROPAGATION = Bound(
        "SNAPSHOT_PROPAGATION",
        Duration.ofSeconds(15),
        "revalidate-interval 10 s (SNP-6) + cold load 2 s (40 % of pipeline.timeout 5 s) + 3 s margin",
    )

    /**
     * A staged change's shadow window closes on the control plane: no earlier than `cosec.control.shadow.min-duration`
     * (5 s, a test value of `profile/shortened-bounds.yaml`; default 15 min), looked at every check interval (a fifteenth
     * of it, 333 ms; a minute at the default), from reports each instance writes every report interval (a fifth of it,
     * at least a second: 1 s; 10 s at the default; CTL-10), once every instance has the staged candidate, which reaches it
     * as a document does ([SNAPSHOT_PROPAGATION]).
     */
    val SHADOW_CLOSE = Bound(
        "SHADOW_CLOSE",
        Duration.ofSeconds(22),
        "min-duration 5 s + check interval 0.33 s + report interval 1 s (derived from min-duration, CTL-10) + SNAPSHOT_PROPAGATION 15 s",
    )

    /**
     * A shadow window closes although the replica that started it was stopped: the check of the open windows is done by
     * the holder of the lease `shadow-windows` (RFC 0007 §5.7), which lasts twice the check interval (0.67 s, a fifteenth
     * of the shortened min-duration, 5 s) and is released when the pass ends, so another replica takes it at the latest 1 s
     * later, plus [SHADOW_CLOSE].
     */
    val SHADOW_CLOSE_AFTER_FAILOVER = Bound(
        "SHADOW_CLOSE_AFTER_FAILOVER",
        Duration.ofSeconds(27),
        "SHADOW_CLOSE 22 s + the shadow lease, twice the check interval (0.67 s, RFC 0007 §5.7) + 3 s margin",
    )

    /**
     * A revocation reaches every process: at once through the invalidation message CoCache sends, and at the latest when a
     * local "not revoked" answer ages out, `cosec.authentication.revocation.cache.local-max-age` (30 s, IDN-26), plus 3 s.
     */
    val REVOCATION_PROPAGATION = Bound(
        "REVOCATION_PROPAGATION",
        Duration.ofSeconds(33),
        "revocation cache local-max-age 30 s (IDN-26) + 3 s margin",
    )

    /**
     * A principal whose tokens were revoked signs in again and is accepted: requests also reject tokens issued up to
     * `cosec.authentication.revocation.skew` (5 s) after the revocation (MEM-4), and `iat` has whole seconds, so up to 6 s,
     * plus 3 s.
     */
    val REVOCATION_SKEW = Bound(
        "REVOCATION_SKEW",
        Duration.ofSeconds(9),
        "revocation skew 5 s + iat granularity 1 s (MEM-4) + 3 s margin",
    )

    /**
     * A decision shows in the metrics a moment after its response: the decision event goes to the `BEST_EFFORT` listeners
     * off the request path through a queue (OBS-1, OBS-3), whose hand-off never blocks the request. A few milliseconds
     * in practice; 5 s as the bound.
     */
    val LISTENER_DELIVERY = Bound(
        "LISTENER_DELIVERY",
        Duration.ofSeconds(5),
        "BEST_EFFORT listeners are fed off the request path (OBS-1, OBS-3); a few ms in practice, 5 s margin",
    )

    /**
     * A decision reaches the audit topic: the Kafka audit listener is `BEST_EFFORT` (the default): the event goes to a
     * queue of 1,024 sends and a thread of its own (OBS-3), the producer batches and sends, and the consumer the scenario
     * runs (about 4 s each, its own timeout) must read it; 20 s is the bound for a record that is normally there at once.
     */
    val AUDIT_DELIVERY = Bound(
        "AUDIT_DELIVERY",
        Duration.ofSeconds(20),
        "BEST_EFFORT Kafka audit listener queue and producer batch (OBS-3), plus one to four 4 s consumer runs",
    )

    /**
     * A tenant that fails closed on lag is refused once the oldest document it reads was last confirmed more than
     * `cosec.authorization.snapshot.fail-closed.max-lag` ago (20 s here, default 30 min; SNP-5), counted from the last
     * confirmation, which may be one `revalidate-interval` (10 s, SNP-6) before Redis went away, plus 3 s.
     */
    val FAIL_CLOSED_LAG = Bound(
        "FAIL_CLOSED_LAG",
        Duration.ofSeconds(33),
        "fail-closed max-lag 20 s (shortened-bounds.yaml, SNP-5) + revalidate-interval 10 s (SNP-6) + 3 s margin",
    )

    /**
     * Every process decides and verifies again after Redis returns, with no restart: its clients reconnect (Lettuce's
     * default reconnect delay doubles up to 30 s, not tuned by CoSec), and the next revalidation pass
     * (`revalidate-interval`, 10 s, SNP-6) confirms the documents, plus 5 s.
     */
    val REDIS_RECOVERY = Bound(
        "REDIS_RECOVERY",
        Duration.ofSeconds(45),
        "Lettuce reconnect delay up to 30 s (library default) + revalidate-interval 10 s (SNP-6) + 5 s margin",
    )

    /** A restarted replica turns ready: the image starts (its AOT cache) and installs the documents; the compose health check's own budget. */
    val REPLICA_START = Bound(
        "REPLICA_START",
        Duration.ofSeconds(120),
        "container start with the AOT cache about 20 s + SNAPSHOT_PROPAGATION 15 s, with a wide margin for a loaded runner",
    )
}
