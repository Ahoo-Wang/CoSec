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

package me.ahoo.cosec.control.sdk

import me.ahoo.cosec.annotations.ExperimentalCoSecApi

/** The revision of the document a [Change] was proposed on, and its digest once the change was checked. */
@ExperimentalCoSecApi
class ChangeBase(val revision: Long, val digest: String? = null) {
    override fun equals(
        other: Any?
    ): Boolean = other is ChangeBase && revision == other.revision && digest == other.digest

    override fun hashCode(): Int = listOf(revision, digest).hashCode()

    override fun toString(): String = describe("ChangeBase", "revision" to revision, "digest" to digest)
}
