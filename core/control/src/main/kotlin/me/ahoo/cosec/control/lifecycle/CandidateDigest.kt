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
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.PolicyDocumentCodec
import java.time.Instant

/**
 * The digest of a candidate (RFC 0007 §7.5): SHA-256 of its body encoded with a fixed header, so it depends only on
 * the policies, bindings and sections a change would publish. Approvals and shadow reports name it.
 */
@InternalCoSecApi
object CandidateDigest {
    private val EPOCH: Instant = Instant.EPOCH

    fun of(candidate: PolicyDocument): String = EntryDigest.hex(
        PolicyDocumentCodec.encode(
            candidate.copy(revision = 1, publishedAt = EPOCH, provenance = null),
        ),
    )
}
