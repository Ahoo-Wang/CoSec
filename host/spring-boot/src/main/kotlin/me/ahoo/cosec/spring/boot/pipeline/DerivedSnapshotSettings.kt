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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.spring.boot.pipeline

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.shadow.ShadowReporter
import me.ahoo.cosec.spring.boot.control.ControlProperties
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.Environment
import java.time.Duration

/**
 * What the data plane computes where it used to be told (the rule of root `AGENTS.md`: what can be computed is not
 * configured), and the one startup line that says what it came to and from what.
 */
internal object DerivedSnapshotSettings {
    /** The control plane's key an instance reads: a shadow window it reports for closes no earlier than this. */
    private const val MIN_DURATION_KEY = ControlProperties.PREFIX + ".shadow.min-duration"

    /**
     * The shortest a shadow window lives, as the control plane is configured (`cosec.control.shadow.min-duration`): an
     * instance that runs beside it, or shares its configuration, reads the value; elsewhere the default applies, and so
     * does the default cadence it gives.
     */
    fun windowMinDuration(environment: Environment): Duration =
        Binder.get(environment).bind(MIN_DURATION_KEY, Duration::class.java)
            .orElseGet { ControlProperties.Shadow().minDuration }

    /** How often this instance writes its reports and its status ([ShadowReporter.derivedInterval]). */
    fun reportInterval(environment: Environment): Duration =
        ShadowReporter.derivedInterval(windowMinDuration(environment))

    /** The startup line: the values of the snapshot group, and what each was derived from. */
    fun line(
        properties: RuntimeAuthorizationProperties,
        pipelineTimeout: Duration,
        environment: Environment,
    ): String {
        val snapshot = properties.snapshot
        val derived = snapshot.derived(pipelineTimeout)
        val report = if (properties.shadow.enabled) {
            "; shadow report interval=${reportInterval(environment)} (the status's life ÷ ${ShadowReporter.REPORTS_PER_STATUS}, " +
                "and a ${ShadowReporter.REPORTS_PER_WINDOW}th of the window's shortest life ${windowMinDuration(environment)})"
        } else {
            ""
        }
        return "CoSec derived, not configured: $derived, from cosec.authorization.snapshot.revalidate-interval " +
            "${snapshot.revalidateInterval}, cosec.pipeline.timeout $pipelineTimeout and " +
            "${Runtime.getRuntime().availableProcessors()} cores$report."
    }
}
