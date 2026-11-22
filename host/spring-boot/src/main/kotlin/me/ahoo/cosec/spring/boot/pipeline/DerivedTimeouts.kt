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
import me.ahoo.cosec.runtime.StageBudgets
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.Environment
import java.time.Duration
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

/**
 * The timeouts the starter derives from the two deadlines an operator states, `cosec.pipeline.timeout` and Spring's
 * `spring.lifecycle.timeout-per-shutdown-phase` (`PIP-33`, `OBS-10`; the rule of root `AGENTS.md`: what can be
 * computed is not configured), and the part of the startup line that says what each came to and from what. Read from
 * the environment, so that every bean that needs one, in any auto-configuration, reads the same value.
 */
internal object DerivedTimeouts {
    private const val PIPELINE_TIMEOUT_KEY = PipelineProperties.PREFIX + ".timeout"
    private const val SHUTDOWN_PHASE_KEY = "spring.lifecycle.timeout-per-shutdown-phase"

    /** Spring Boot's default of [SHUTDOWN_PHASE_KEY]. */
    private val SPRING_SHUTDOWN_PHASE: Duration = Duration.ofSeconds(30)

    /**
     * How many revalidation intervals an instance serves its local copy of a snapshot document before it asks Redis
     * again: the copy serves cold loads and writers only, installed documents are confirmed every interval (`SNP-6`).
     * Three times the default 10 s is the 30 s it was.
     */
    const val SNAPSHOT_LOCAL_COPY_INTERVALS: Int = 3

    fun pipelineTimeout(environment: Environment): Duration =
        Binder.get(environment).bind(PIPELINE_TIMEOUT_KEY, Duration::class.java)
            .orElseGet { PipelineProperties().timeout }

    fun budgets(environment: Environment): StageBudgets =
        StageBudgets.of(pipelineTimeout(environment).toKotlinDuration())

    fun shutdownPhase(environment: Environment): Duration =
        Binder.get(environment).bind(SHUTDOWN_PHASE_KEY, Duration::class.java).orElseGet { SPRING_SHUTDOWN_PHASE }

    /** How long each set of listeners delivers what is queued at shutdown. */
    fun drain(environment: Environment): Duration =
        StageBudgets.drain(shutdownPhase(environment).toKotlinDuration()).toJavaDuration()

    /**
     * How long a REQUIRED change listener may take: the control request's own bound. No deadline of its own bounds a
     * control request, and its write is authorized through the pipeline, whose deadline is `cosec.pipeline.timeout`.
     */
    fun controlRequiredListener(environment: Environment): Duration = pipelineTimeout(environment)

    fun snapshotLocalMaxAge(revalidateInterval: Duration): Duration =
        revalidateInterval.multipliedBy(SNAPSHOT_LOCAL_COPY_INTERVALS.toLong())

    /** The timeouts' part of the startup line. */
    fun line(environment: Environment, revalidateInterval: Duration): String =
        "stage budgets ${budgets(environment)}; " +
            "control required listener=${controlRequiredListener(environment)} (the pipeline timeout); " +
            "listener drain=${drain(environment)} (a ${StageBudgets.DRAINS_PER_SHUTDOWN_PHASE}th of " +
            "$SHUTDOWN_PHASE_KEY ${shutdownPhase(environment)}); " +
            "snapshot local-max-age=${snapshotLocalMaxAge(revalidateInterval)} ($SNAPSHOT_LOCAL_COPY_INTERVALS × the " +
            "revalidate interval)"
}
