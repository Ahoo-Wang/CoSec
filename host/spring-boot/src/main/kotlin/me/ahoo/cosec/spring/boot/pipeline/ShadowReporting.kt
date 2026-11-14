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

package me.ahoo.cosec.spring.boot.pipeline

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.shadow.ShadowReporter
import me.ahoo.cosec.spring.boot.StartupTask

/**
 * Runs [reporter] every interval from the start of the context until it closes. A [StartupTask] (`STA-3`): nothing
 * depends on it, and lazily created it would never report, so this instance would count for nothing in a shadow window.
 */
@InternalCoSecApi
class ShadowReporting(val reporter: ShadowReporter) : AutoCloseable, StartupTask {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("cosec-shadow-reporter"))

    init {
        scope.launch { reporter.run() }
    }

    override fun close() {
        scope.cancel()
    }
}
