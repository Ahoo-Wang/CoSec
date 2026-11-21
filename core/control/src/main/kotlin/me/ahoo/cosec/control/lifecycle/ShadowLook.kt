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

package me.ahoo.cosec.control.lifecycle

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.Acknowledgement
import me.ahoo.cosec.control.change.ShadowSummary

/**
 * Every instance that has reported on a window so far, its [summary] once it may close, the instances it is [waiting]
 * for (they reported before and have no report now), and which live instances acknowledge it ([acknowledgement]).
 */
@InternalCoSecApi
class ShadowLook(
    val reporters: Set<String>,
    val summary: ShadowSummary?,
    val waiting: Set<String> = emptySet(),
    val acknowledgement: Acknowledgement = Acknowledgement(0, emptyList()),
)
