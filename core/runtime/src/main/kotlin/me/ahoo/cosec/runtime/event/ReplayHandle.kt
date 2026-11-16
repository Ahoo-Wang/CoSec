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

package me.ahoo.cosec.runtime.event

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.EngineRequest
import me.ahoo.cosec.engine.SnapshotView

/**
 * What a shadow listener needs to evaluate the request again (RFC 0006 §9.5.4): the request, the view and the external
 * attributes already fetched. It keeps the view reachable, so the pipeline creates one only when a shadow listener
 * sampled the request ([sampledBy]).
 */
@InternalCoSecApi
class ReplayHandle internal constructor(
    internal val request: EngineRequest,
    internal val view: SnapshotView,
    internal val sampledBy: Set<String>,
)
