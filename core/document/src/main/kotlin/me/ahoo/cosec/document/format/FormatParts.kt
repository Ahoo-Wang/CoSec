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
import me.ahoo.cosec.document.format.DocumentFormats.SNAPSHOT_DOCUMENT
import me.ahoo.cosec.document.format.FormatParts.ref

/** What more than one section's formats refer to: the `$ref`, an actor and the condition. Depends on nothing. */
internal object FormatParts {
    internal fun ref(file: String, definition: String? = null) = Ref("$file.schema.json", definition)

    internal val actor = ObjectType(
        listOf(
            Field("tenant", StringType(minLength = 1, examples = listOf("acme", "(platform)")), required = true),
            Field("principal", StringType(minLength = 1, examples = listOf("alice", "ops-1")), required = true),
        ),
    )

    internal val astCondition = ref(SNAPSHOT_DOCUMENT, "condition")
}
