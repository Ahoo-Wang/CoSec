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

package me.ahoo.cosec.migrate

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.apikey.ApiKey

class ConvertedApiKey(
    val legacy: LegacyApiKey,
    val key: ApiKey,
    val mode: Mode,
    /**
     * The principal's own bindings that grant (principal and break-glass) the key reached as that principal, and no
     * longer does.
     */
    val lost: List<String>,
    /** What depends on the principal's id and changes with it; empty when the key keeps its principal. */
    val changes: List<String> = emptyList(),
    /** The DENYs bound to the principal, which the key, capped on it, stays under (RC1-1); none for a moved key. */
    val denied: List<String> = emptyList(),
    /**
     * For a key moved without `--blacklist`: the value to add to the application's blacklist before the key is used if
     * its 7.x principal is blocked there (DR2-M-9); null otherwise.
     */
    val blockIfBlocked: String? = null,
) {
    enum class Mode { IN_PLACE, OWN_PRINCIPAL, GRANTED }
}
