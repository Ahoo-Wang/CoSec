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

package me.ahoo.cosec.runtime.shadow

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.flat.CompiledDocument
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/** A candidate as an instance holds it: compiled, or why it could not be. */
@InternalCoSecApi
sealed interface Candidate {
    val window: String

    @InternalCoSecApi
    class Ready(override val window: String, val baseRevision: Long, val document: CompiledDocument) : Candidate

    /** The candidate does not compile on this instance: `CANDIDATE_ERROR`. */
    @InternalCoSecApi
    class Failed(override val window: String, val error: Throwable) : Candidate
}

/** The candidates the shadow listener compares against (RFC 0007 §6.4). Asked off the request's thread only. */
@InternalCoSecApi
fun interface CandidateSource {
    /** The candidate of [key], or null when none is staged or it cannot be read now. */
    suspend fun candidate(key: SnapshotKey): Candidate?
}
