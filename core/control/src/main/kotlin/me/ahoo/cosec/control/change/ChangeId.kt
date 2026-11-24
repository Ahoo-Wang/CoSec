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

package me.ahoo.cosec.control.change

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Identifiers

/**
 * The id of a change request (RFC 0007 §5.2), a sortable id from the host's generator. It holds none of the characters
 * the stores split on (DR1-F-21): `:` (a key's parts), `#` and `@` (an open-change set's member, `<docKey>#<id>@<digest>`,
 * split at its last `#` and the `@` after it), `/` (a path segment of the control API); CoSec's own generators never
 * make one, and an [me.ahoo.cosec.spi.id.IdGenerator] of the application's that did would list one change as another.
 */
@JvmInline
@InternalCoSecApi
value class ChangeId(val value: String) {
    init {
        require(
            value.isNotEmpty() && value.none { it in SEPARATORS || it.isWhitespace() || it.isISOControl() } &&
                Identifiers.isWellFormed(value)
        ) {
            "A change id is not empty and has no ':', '#', '@', '/', whitespace, control characters or unpaired " +
                "surrogates: [${Identifiers.printable(value)}]."
        }
    }

    private companion object {
        const val SEPARATORS = ":#@/"
    }
}
