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

package me.ahoo.cosec.runtime

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.event.DecisionListeners

/**
 * How the decision listeners of a pipeline are run (RFC 0006 §9), set in [PipelineBuilder.listeners]. The property is
 * the starter's `cosec.observability.listener.queue-capacity`, with the same default. Neither timeout is set (`OBS-10`):
 * a REQUIRED listener may take [StageBudgets.REQUIRED_LISTENER_PERCENT] % of [PipelineSettings.timeout], and
 * [Pipeline.close] lets the BEST_EFFORT listeners deliver what is queued for
 * [DecisionListeners.DEFAULT_DRAIN_TIMEOUT], the starter's derived drain at Spring's default shutdown phase.
 */
@ExperimentalCoSecApi
class ListenerSettings internal constructor() {
    /** The events one BEST_EFFORT listener may have waiting (`queue-capacity`); beyond it, events are dropped. */
    var queueCapacity: Int = DecisionListeners.DEFAULT_QUEUE_CAPACITY
}
