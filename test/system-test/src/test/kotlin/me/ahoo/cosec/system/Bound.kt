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
}
