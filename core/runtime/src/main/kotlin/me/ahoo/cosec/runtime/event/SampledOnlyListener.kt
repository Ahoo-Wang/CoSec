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

package me.ahoo.cosec.runtime.event

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.SnapshotView
import me.ahoo.cosec.model.scope.Scope

/**
 * A BEST_EFFORT listener that reads only the events whose [ReplayHandle] names it (`OBS-8`): the shadow listener. The
 * pipeline offers it no other event, and when every BEST_EFFORT listener is one and none sampled a request, it makes no
 * event for that request at all. A REQUIRED listener's events are made and delivered whatever these sample.
 */
@InternalCoSecApi
interface SampledOnlyListener : DecisionListener {
    /**
     * Whether it samples the request in [scope] decided on [view]: asked on the request's thread before the event is made,
     * so it must be cheap and never block. Only then does the event carry a [ReplayHandle] naming it.
     */
    fun sample(scope: Scope, view: SnapshotView): Boolean
}
