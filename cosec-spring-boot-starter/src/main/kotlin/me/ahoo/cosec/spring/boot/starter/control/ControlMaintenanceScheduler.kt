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

package me.ahoo.cosec.spring.boot.starter.control

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.ahoo.cosec.control.store.ControlMaintenance
import org.springframework.context.SmartLifecycle
import java.time.Duration
import kotlin.time.toKotlinDuration

private val log = KotlinLogging.logger {}

/**
 * Runs the control plane's periodic tasks on every instance while the application runs: [ControlMaintenance.reclaim]
 * every [reclaimInterval] and [ControlMaintenance.closeShadowWindows] every [shadowInterval], each after one interval
 * first. Leases keep two instances from doing the same work; a failed pass is logged and the next one tries again.
 */
class ControlMaintenanceScheduler(
    private val maintenance: ControlMaintenance,
    private val reclaimInterval: Duration,
    private val shadowInterval: Duration,
) : SmartLifecycle {
    init {
        require(reclaimInterval > Duration.ZERO) { "cosec.control.revisions.reclaim-interval must be positive." }
        require(shadowInterval > Duration.ZERO) { "cosec.control.shadow.check-interval must be positive." }
    }

    @Volatile
    private var scope: CoroutineScope? = null

    override fun start() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("cosec-control-maintenance"))
        scope.every(reclaimInterval) { maintenance.reclaim() }
        scope.every(shadowInterval) { maintenance.closeShadowWindows() }
        this.scope = scope
    }

    /**
     * Runs [pass] every [interval] until [stop]. Whatever a pass throws, a timeout's cancellation or an `Error` included,
     * is logged and the next pass runs; only the cancellation of this scheduler's own scope ends the loop.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun CoroutineScope.every(interval: Duration, pass: suspend () -> Unit) = launch {
        while (isActive) {
            delay(interval.toKotlinDuration())
            try {
                pass()
            } catch (e: Throwable) {
                currentCoroutineContext().ensureActive()
                log.warn(e) { "A control-plane maintenance pass failed; the next one tries again." }
            }
        }
    }

    override fun stop() {
        scope?.cancel()
        scope = null
    }

    override fun isRunning(): Boolean = scope != null
}
