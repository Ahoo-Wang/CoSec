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

package me.ahoo.cosec.document.format

import me.ahoo.cosec.annotations.InternalCoSecApi

/** One generated `schema/<name>.schema.json`. */
@InternalCoSecApi
class SchemaFile(
    val name: String,
    val title: String,
    /** Null for a file that only holds shared definitions. */
    val root: FormatType?,
    val definitions: Map<String, FormatType> = emptyMap(),
    val description: String? = null,
) {
    val fileName: String get() = "$name.schema.json"

    fun ref(definition: String? = null): Ref {
        require(definition == null || definition in definitions) { "$fileName has no definition [$definition]." }
        return Ref(fileName, definition)
    }
}
