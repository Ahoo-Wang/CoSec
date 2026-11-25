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

package me.ahoo.cosec.compat.side

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.identity.IdentityFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException

/** What a refusal is, in words both versions share: its kind, and the reason a version names (`format:later`, `format:malformed`). */
internal object Refusals {
    fun of(error: Throwable): String {
        // A store's cache wraps what its codec refused: the codec's refusal is the answer.
        val causes = generateSequence(error) { it.cause }.take(DEPTH).toList()
        causes.firstNotNullOfOrNull { it as? IdentityFormatException }?.let { return "identity:${it.reason.name}" }
        // A stored record a later version wrote, apart from one that is not a record (SNP-27): what the reader told.
        if (causes.any { it is SnapshotFormatException }) return "format:" + RefusalReasons.of(error)
        return "${error.javaClass.simpleName}: ${error.message?.take(200)}"
    }

    private const val DEPTH = 8
}
