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

package me.ahoo.cosec.spi.shadow

import me.ahoo.cosec.annotations.CoSecSpi
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi

/** Where each instance writes its shadow reports and its status (RFC 0007 §6.4, §6.5). */
@CoSecSpi
@ExperimentalCoSecApi
interface ShadowReportSink {
    suspend fun write(report: ShadowReport)

    suspend fun write(status: InstanceStatus)

    /**
     * Writes [resident], the documents its instance holds, as a record of its own beside the status (`CTL-75`), with a
     * life of [ShadowReportReader.RESIDENT_TTL]. An instance writes it before its status when it changed, and in any case
     * before half that life has passed.
     */
    suspend fun write(resident: ResidentDocuments)
}
