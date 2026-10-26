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

package me.ahoo.cosec.snapshot.format

import java.util.TreeMap

/**
 * Writes a [SchemaFile] as a draft-07 JSON Schema: object keys sorted, two-space indentation, a trailing newline, so
 * the same descriptor always gives the same bytes. Hand-written rather than through Jackson so the output does not
 * depend on a mapper's configuration.
 */
object JsonSchemaWriter {
    const val DRAFT_07 = "http://json-schema.org/draft-07/schema#"

    fun write(file: SchemaFile, baseUri: String = DocumentFormats.BASE_URI): String {
        val document = TreeMap<String, Any>()
        document["\$schema"] = DRAFT_07
        document["\$id"] = baseUri + file.fileName
        document["title"] = file.title
        file.description?.let { document["description"] = it }
        file.root?.let { document.putAll(schemaOf(it, file)) }
        if (file.definitions.isNotEmpty()) {
            document["definitions"] = file.definitions.mapValuesTo(TreeMap()) { schemaOf(it.value, file) }
        }
        return StringBuilder().also { it.appendValue(document, 0) }.append('\n').toString()
    }

    private fun schemaOf(type: FormatType, file: SchemaFile): TreeMap<String, Any> {
        val schema = TreeMap<String, Any>()
        when (type) {
            is StringType -> stringSchema(type, schema)
            is BooleanType -> schema["type"] = "boolean"
            is IntegerType -> {
                schema["type"] = "integer"
                type.minimum?.let { schema["minimum"] = it }
                type.const?.let { schema["const"] = it }
            }

            is NumberType -> {
                schema["type"] = "number"
                type.exclusiveMinimum?.let { schema["exclusiveMinimum"] = it }
            }

            is ArrayType, is TupleType -> arraySchema(type, file, schema)

            is MapType -> {
                schema["type"] = "object"
                schema["propertyNames"] = schemaOf(type.keys, file)
                schema["additionalProperties"] = schemaOf(type.values, file)
            }

            is ObjectType -> objectSchema(type, file, schema)
            is AnyOf -> schema["anyOf"] = type.variants.map { schemaOf(it, file) }
            is Ref -> schema["\$ref"] = target(type, file)
        }
        type.description?.let { schema["description"] = it }
        return schema
    }

    private fun stringSchema(type: StringType, schema: TreeMap<String, Any>) {
        schema["type"] = "string"
        type.enum.takeIf { it.isNotEmpty() }?.let { schema["enum"] = it }
        type.pattern?.let { schema["pattern"] = it }
        type.minLength?.let { schema["minLength"] = it }
        type.maxLength?.let { schema["maxLength"] = it }
        type.format?.let { schema["format"] = it }
        type.examples.takeIf { it.isNotEmpty() }?.let { schema["examples"] = it }
    }

    private fun arraySchema(type: FormatType, file: SchemaFile, schema: TreeMap<String, Any>) {
        schema["type"] = "array"
        if (type is TupleType) {
            // Draft 7 tuples: positional items, nothing more, nothing less.
            schema["items"] = type.items.map { schemaOf(it, file) }
            schema["additionalItems"] = false
            schema["minItems"] = type.items.size
            return
        }
        type as ArrayType
        schema["items"] = schemaOf(type.items, file)
        type.minItems?.let { schema["minItems"] = it }
        if (type.uniqueItems) {
            schema["uniqueItems"] = true
        }
    }

    private fun objectSchema(type: ObjectType, file: SchemaFile, schema: TreeMap<String, Any>) {
        schema["type"] = "object"
        if (type.fields.isNotEmpty()) {
            schema["properties"] = type.fields.associateTo(TreeMap()) { field ->
                field.name to schemaOf(field.type, file).also { fieldSchema ->
                    field.description?.let { fieldSchema["description"] = it }
                    field.default?.let { fieldSchema["default"] = it }
                }
            }
        }
        type.fields.filter { it.required }.map { it.name }.takeIf { it.isNotEmpty() }?.let { schema["required"] = it }
        schema["additionalProperties"] = type.extension?.let { mapOf("description" to it) } ?: false
        type.minProperties?.let { schema["minProperties"] = it }
        type.maxProperties?.let { schema["maxProperties"] = it }
        if (type.requireAnyOf.isNotEmpty()) {
            schema["anyOf"] = type.requireAnyOf.map { mapOf("required" to listOf(it)) }
        }
        if (type.dependencies.isNotEmpty()) {
            schema["dependencies"] = TreeMap(type.dependencies)
        }
    }

    private fun target(ref: Ref, file: SchemaFile): String {
        val pointer = ref.definition?.let { "#/definitions/$it" }
        if (ref.file == file.fileName) {
            return pointer ?: "#"
        }
        return ref.file + pointer.orEmpty()
    }

    private fun StringBuilder.appendValue(value: Any, depth: Int) {
        when (value) {
            is Map<*, *> -> appendContainer(
                '{',
                '}',
                value.entries.sortedBy { it.key as String },
                depth
            ) { (key, child) ->
                appendString(key as String)
                append(": ")
                appendValue(requireNotNull(child), depth + 1)
            }

            is List<*> -> appendContainer('[', ']', value, depth) { appendValue(requireNotNull(it), depth + 1) }
            is String -> appendString(value)
            is Boolean, is Int, is Long -> append(value)
            else -> throw IllegalArgumentException("Cannot write ${value.javaClass}.")
        }
    }

    private inline fun <T> StringBuilder.appendContainer(
        open: Char,
        close: Char,
        items: List<T>,
        depth: Int,
        appendItem: StringBuilder.(T) -> Unit,
    ) {
        append(open)
        if (items.isEmpty()) {
            append(close)
            return
        }
        items.forEachIndexed { index, item ->
            append(if (index == 0) "\n" else ",\n")
            indent(depth + 1)
            appendItem(item)
        }
        append('\n')
        indent(depth)
        append(close)
    }

    private fun StringBuilder.indent(depth: Int) {
        repeat(depth) { append("  ") }
    }

    private fun StringBuilder.appendString(value: String) {
        append('"')
        for (char in value) {
            when {
                char == '"' -> append("\\\"")
                char == '\\' -> append("\\\\")
                char == '\n' -> append("\\n")
                char < ' ' -> append("\\u%04x".format(char.code))
                else -> append(char)
            }
        }
        append('"')
    }
}
