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

/*
 * Package me.ahoo.cosec.runtime.stage: the request path, as named stages over one RequestState (RFC 0013 §4.2). The
 * pipeline runs its stages in order within its deadline; each reads what the stages before it wrote, writes its own
 * fields, and either ends the request with an Outcome built by StatusMapping or lets it go on.
 */
package me.ahoo.cosec.runtime.stage

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.cosec.model.decision.Cause
import me.ahoo.cosec.runtime.http.Outcome
import me.ahoo.cosec.runtime.http.StatusMapping
import me.ahoo.cosec.runtime.http.Unavailability

/** The request path logs as `me.ahoo.cosec.runtime.Pipeline`, whichever stage writes: one name to configure. */
internal val requestPathLog: KLogger = KotlinLogging.logger("me.ahoo.cosec.runtime.Pipeline")

/** One step of the request path: the outcome that ends the request, or null to go on to the next stage. */
internal interface Stage {
    suspend fun run(state: RequestState): Outcome?
}

/** The 503 of a decision that is indeterminate for [cause] (RFC 0006 §6.1). */
internal fun RequestState.unavailable(cause: Cause): Outcome =
    StatusMapping.unavailable(requestId, Unavailability.Indeterminate(cause), traceId)
