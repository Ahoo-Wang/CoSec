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

package me.ahoo.cosec.runtime.stage

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.call.FallbackIds
import me.ahoo.cosec.runtime.event.DecisionListeners
import me.ahoo.cosec.runtime.event.ReplayHandle
import me.ahoo.cosec.runtime.event.SampledOnlyListener
import me.ahoo.cosec.runtime.http.Headers
import me.ahoo.cosec.runtime.http.Outcome

/**
 * Last, the BEST_EFFORT listeners (RFC 0006 §9.3), once the outcome exists: offered without suspending, and handled
 * elsewhere. A null outcome is an external cancellation, whose event is CANCELLED (PIP-3). Without a BEST_EFFORT
 * listener no event is made: the REQUIRED ones had theirs. Nor when every BEST_EFFORT listener reads only the events it
 * sampled (the shadow listeners) and none sampled this request (`OBS-8`): the event would be offered to no one.
 */
internal class PublishBestEffort(private val listeners: DecisionListeners, private val ids: FallbackIds) {
    /** The shadow listeners, asked whether they sample a request before its event is made (§9.5.4, `OBS-8`). */
    private val shadows: List<SampledOnlyListener> =
        listeners.bestEffortListeners.filterIsInstance<SampledOnlyListener>()

    fun run(state: RequestState, outcome: Outcome?) {
        if (!listeners.hasBestEffort) {
            return
        }
        val replay = if (outcome == null) null else replay(state)
        if (replay == null && listeners.sampledOnly) {
            return
        }
        listeners.publish(state.event(ids.generate(), outcome, replay))
    }

    /** What a shadow listener needs, only when one samples this request. */
    private fun replay(state: RequestState): ReplayHandle? {
        if (shadows.isEmpty() || !state.decided) {
            return null
        }
        var sampled: MutableSet<String>? = null
        for (shadow in shadows) {
            if (shadow.sample(state.request.scope, state.view)) {
                (sampled ?: HashSet<String>().also { sampled = it }) += shadow.name
            }
        }
        return sampled?.let {
            // The replay reads the request's headers after the response, when the host may have recycled its request:
            // a view over them is copied now, while they are still the request's (R2B-8).
            Headers.freeze(state.facts.headers)
            ReplayHandle(state.request, state.view, it)
        }
    }
}
