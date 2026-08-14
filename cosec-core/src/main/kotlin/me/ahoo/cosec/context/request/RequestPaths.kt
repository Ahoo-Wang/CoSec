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

package me.ahoo.cosec.context.request

import org.springframework.web.util.UriUtils

/**
 * Guards request paths against traversal segments before any authorization matching:
 * matching machinery percent-decodes segments, so an encoded `..` would otherwise be
 * authorized under a public wildcard pattern and normalized by the downstream container.
 */
object RequestPaths {

    fun requireSafe(path: String) {
        if (isTraversal(path)) {
            throw InvalidRequestPathException()
        }
    }

    fun isTraversal(path: String): Boolean {
        if ('%' !in path && '.' !in path) {
            return false
        }
        val decodedPath = try {
            UriUtils.decode(path, Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            // fail-closed: a malformed percent-escape is never a safe traversal-clean path.
            return true
        }
        return decodedPath.split('/').any { it == "." || it == ".." }
    }
}
