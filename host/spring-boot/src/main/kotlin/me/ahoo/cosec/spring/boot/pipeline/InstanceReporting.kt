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

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.shadow.InstanceReporter
import me.ahoo.cosec.spring.boot.StartupTask
import java.time.Duration

/**
 * Reports this instance's status from the start of the context until it closes (RFC 0014 R5, `SNP-25`). The first report
 * is written as this bean is created, which the snapshot source waits for, so the instance reports before it reads a
 * document; then [InstanceReporter.run] every interval, quiet while the shadow reporter drives the reports. A
 * [StartupTask]: nothing else needs it, and lazily created it would never report.
 */
@InternalCoSecApi
class InstanceReporting(val reporter: InstanceReporter) : AutoCloseable, StartupTask {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("cosec-instance-reporter"))

    init {
        // Bounded: a Redis that does not answer delays the start by this much at most, and the loop tries again.
        runBlocking {
            withTimeoutOrNull(
                FIRST_REPORT_BUDGET.toMillis()
            ) { reporter.report(windows = null, shadows = false) }
        }
        scope.launch { reporter.run() }
    }

    override fun close() {
        scope.cancel()
    }

    @InternalCoSecApi
    companion object {
        /** How long the start waits for the first report. */
        val FIRST_REPORT_BUDGET: Duration = Duration.ofSeconds(2)
    }
}
