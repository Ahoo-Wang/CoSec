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

package me.ahoo.cosec.document.route

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.Identifiers

/**
 * A statement a mapped route escapes (UC-1): [statement] (`DENY <policy>#<index>` or `rate limit <id>`) would apply to
 * [route]'s requests as `http:<METHOD>` on their path, and does not apply to them as the route's own action and
 * resource. [location] is the route map the route came from.
 *
 * Its text goes into a host's log line and a control-plane finding, and [statement] holds an id as the document wrote
 * it, which nothing checks for control characters: each part is [shown], so no document forges a line (`DOC-12`).
 */
@InternalCoSecApi
data class RouteEscape(val statement: String, val route: Route, val location: String) {
    override fun toString(): String =
        "${shown(statement)} escapes ${shown(route.method)} ${shown(route.path)} " +
            "(${shown(route.action)} on ${shown(route.resource)}, ${shown(location)})"

    companion object {
        /** The most characters of one part a line shows: an id is as long as its document wrote it. */
        const val MAX_PART = 128

        /**
         * [text] as a line shows it: a control character or an unpaired surrogate as its `\uXXXX` escape
         * ([Identifiers.printable], RC1-15), then cut at [MAX_PART] characters, never inside a surrogate pair, saying
         * how long it was.
         */
        fun shown(text: String): String {
            val printable = Identifiers.printable(text)
            if (printable.length <= MAX_PART) {
                return printable
            }
            val end = if (printable[MAX_PART - 1].isHighSurrogate()) MAX_PART - 1 else MAX_PART
            return "${printable.take(end)}… (${printable.length} characters)"
        }
    }
}
