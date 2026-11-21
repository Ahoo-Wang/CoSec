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

package me.ahoo.cosec.spring.boot

import org.springframework.boot.context.properties.source.ConfigurationPropertyName

/**
 * The `cosec.*` keys a preview of 8.0 had and the next removed because their value is computed or constant (the rule of
 * root `AGENTS.md`: what can be computed is not configured), each with what replaced it. [UnknownProperties] says so
 * instead of only "unknown": a configuration that still sets one is told it does nothing, and why. Kept until 8.0.0 is
 * released, when the upgrade page is the record.
 */
internal object RemovedProperties {
    /** Key to what replaced it, in the words of the message: `<key> (no longer a property: <this>)`. */
    private val REMOVED: Map<String, String> = linkedMapOf(
        "cosec.authorization.snapshot.lag-warn" to
            "computed: 6 passes of cosec.authorization.snapshot.revalidate-interval",
        "cosec.authorization.snapshot.lag-escalate" to
            "computed: 30 passes of cosec.authorization.snapshot.revalidate-interval",
        "cosec.authorization.snapshot.cold-load-timeout" to
            "computed: 40 % of cosec.pipeline.timeout",
        "cosec.authorization.snapshot.compile-threads" to
            "computed from the cores: one thread per 4 cores, at least 1 and at most 4",
        "cosec.authorization.snapshot.reload-concurrency" to
            "a constant, 16: only the burst of a revalidation pass depends on it",
        "cosec.authorization.shadow.report-interval" to
            "computed: a sixth of the status's life (10 s), and a fifth of cosec.control.shadow.min-duration, at least 1 s",
        "cosec.authentication.revocation.cache.initial-capacity" to
            "computed by the cache from cosec.authentication.revocation.cache.maximum-size",
        "cosec.authentication.revocation.cache.expire-after-access" to
            "cosec.authentication.revocation.cache.local-max-age already expires every entry",
        "cosec.control.revisions.orphan-age" to
            "computed: one reclaim pass (1 hour)",
        "cosec.control.revisions.reclaim-interval" to
            "a constant, 1 hour: cosec.control.revisions.min-age and max-count decide what is reclaimed",
        "cosec.control.shadow.check-interval" to
            "computed: a fifteenth of cosec.control.shadow.min-duration, between 250 ms and 1 minute",
        "cosec.control.http.max-body-size" to
            "computed: the 8 MB document cap and 2 MB for the operations around it",
        "cosec.control.maintenance.enabled" to
            "every control-plane instance runs the maintenance passes, kept apart by their leases",
    )

    private val byName: Map<ConfigurationPropertyName, String> =
        REMOVED.mapKeys { ConfigurationPropertyName.of(it.key) }

    /** What replaced [key], when it is one of the removed ones (compared as Spring's binder compares names). */
    fun replacement(key: String): String? =
        ConfigurationPropertyName.ofIfValid(key)?.let { byName[it] }

    /** The removed keys, for tests and for the upgrade page's check. */
    val keys: Set<String> get() = REMOVED.keys
}
