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

package me.ahoo.cosec.control.change

import me.ahoo.cosec.runtime.document.format.AnyOf
import me.ahoo.cosec.runtime.document.format.ArrayType
import me.ahoo.cosec.runtime.document.format.DocumentFormats
import me.ahoo.cosec.runtime.document.format.FormatType
import me.ahoo.cosec.runtime.document.format.MapType
import me.ahoo.cosec.runtime.document.format.ObjectType
import me.ahoo.cosec.runtime.document.format.StringType
import me.ahoo.cosec.runtime.document.format.TupleType
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * The ids the format descriptors show as examples, and so the generated schema files and editors, are ids a change may
 * put: none uses a prefix [ChangeChecks] reserves for built-in policies and `cosec-migrate` (V2).
 */
class DescriptorExampleIdsTest {
    private fun ids(type: FormatType, into: MutableList<String>) {
        when (type) {
            is ObjectType -> type.fields.forEach { field ->
                (field.type as? StringType)?.takeIf { field.name == "id" }?.let { into += it.examples }
                ids(field.type, into)
            }

            is ArrayType -> ids(type.items, into)
            is MapType -> ids(type.values, into)
            is TupleType -> type.items.forEach { ids(it, into) }
            is AnyOf -> type.variants.forEach { ids(it, into) }
            else -> Unit
        }
    }

    @Test
    fun noExampleIdUsesAReservedPrefix() {
        val examples = ArrayList<String>()
        DocumentFormats.files.forEach { file ->
            file.root?.let { ids(it, examples) }
            file.definitions.values.forEach { ids(it, examples) }
        }
        examples.assert().contains("deny-ip", "editor-read")
        examples.filter { id -> ChangeChecks.RESERVED.any(id::startsWith) }.assert().isEmpty()
    }
}
