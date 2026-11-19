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
     * plus a cold load (`cold-load-timeout`, 2 s) and 3 s for the requests that observe it.
     */
    val SNAPSHOT_PROPAGATION = Bound(
        "SNAPSHOT_PROPAGATION",
        Duration.ofSeconds(15),
        "revalidate-interval 10 s (SNP-6) + cold-load-timeout 2 s + 3 s margin",
    )

    /**
     * A staged change's shadow window closes on the control plane: no earlier than `cosec.control.shadow.min-duration`
     * (5 s), looked at every `check-interval` (1 s), from reports each instance writes every
     * `cosec.authorization.shadow.report-interval` (1 s), all three test values of `profile/shortened-bounds.yaml`
     * (defaults 15 min, 1 min, 10 s; CTL-10), once every instance has the staged candidate, which reaches it as a document
     * does ([SNAPSHOT_PROPAGATION]).
     */
    val SHADOW_CLOSE = Bound(
        "SHADOW_CLOSE",
        Duration.ofSeconds(22),
        "min-duration 5 s + check-interval 1 s + report-interval 1 s (shortened-bounds.yaml, CTL-10) + SNAPSHOT_PROPAGATION 15 s",
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
