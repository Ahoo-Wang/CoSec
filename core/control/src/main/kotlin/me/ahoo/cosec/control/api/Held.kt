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

package me.ahoo.cosec.control.api

import me.ahoo.cosec.document.PolicyDocument

/**
 * A document to decide on and, for a stored one (a head, or a retained revision), its [entry] in the [DocumentCache]:
 * then it is compiled once for its content (RC1-41). A candidate has none, and is compiled once per call.
 */
internal class Held(val document: PolicyDocument, val entry: DocumentCache.Entry?) {
    companion object {
        fun of(document: PolicyDocument?): Held? = document?.let { Held(it, null) }
    }
}

/** The documents a decision reads, as [Held]. */
internal class HeldDocuments(val platform: Held?, val app: Held?, val tenant: Held?)
