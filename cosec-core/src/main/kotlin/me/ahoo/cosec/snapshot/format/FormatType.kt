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

/**
 * The shape of one JSON value in a document format (RFC 0007 §10.4): plain data that [JsonSchemaWriter] turns into
 * JSON Schema and `SchemaConformanceTest` binds to the hand-written codecs. Nothing here is read by a codec.
 *
 * Only what the formats use is modelled; a new keyword is added here, in [JsonSchemaWriter] and in the test's document
 * generator together.
 */
sealed interface FormatType {
    val description: String?
}

/**
 * A string. [examples] are written to the schema for editor completion and are the values the conformance test
 * generates, so a string whose valid values cannot be generated from [enum] alone must list valid examples.
 */
class StringType(
    val enum: List<String> = emptyList(),
    val pattern: String? = null,
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val format: String? = null,
    val examples: List<String> = emptyList(),
    override val description: String? = null,
) : FormatType

class BooleanType(override val description: String? = null) : FormatType

class IntegerType(
    val minimum: Long? = null,
    val const: Long? = null,
    override val description: String? = null,
) : FormatType

class NumberType(
    val exclusiveMinimum: Long? = null,
    override val description: String? = null,
) : FormatType

class ArrayType(
    val items: FormatType,
    val minItems: Int? = null,
    val uniqueItems: Boolean = false,
    override val description: String? = null,
) : FormatType

/** An object whose keys are data, such as the grants of an app document keyed by role. */
class MapType(
    val keys: StringType,
    val values: FormatType,
    override val description: String? = null,
) : FormatType

/**
 * An object with named [fields]. [extension] is null for a closed object (no unknown fields); otherwise it describes
 * what the other keys are, as for matcher types a factory registers at run time.
 */
class ObjectType(
    val fields: List<Field>,
    val extension: String? = null,
    val minProperties: Int? = null,
    val maxProperties: Int? = null,
    /** At least one of these fields must be present. */
    val requireAnyOf: List<String> = emptyList(),
    /** A present key field requires every field in its value. */
    val dependencies: Map<String, List<String>> = emptyMap(),
    override val description: String? = null,
) : FormatType {
    init {
        val names = fields.map { it.name }
        require(names.toSet().size == names.size) { "Duplicate field in $names." }
        require(names.containsAll(requireAnyOf)) { "requireAnyOf $requireAnyOf names an undeclared field." }
        require(names.containsAll(dependencies.keys + dependencies.values.flatten())) {
            "dependencies $dependencies name an undeclared field."
        }
    }

    val closed: Boolean get() = extension == null

    fun field(name: String): Field? = fields.firstOrNull { it.name == name }
}

class Field(
    val name: String,
    val type: FormatType,
    val required: Boolean = false,
    val description: String? = null,
    /** The value the codec uses when the field is absent: a [String], [Boolean] or [Number]. */
    val default: Any? = null,
)

/** Any one of [variants]; the first is the one the test generator falls back to when it bounds recursion. */
class AnyOf(val variants: List<FormatType>, override val description: String? = null) : FormatType

/** A reference to the root ([definition] null) or a definition of the schema file [file]. */
class Ref(val file: String, val definition: String? = null) : FormatType {
    override val description: String? get() = null

    override fun toString(): String = if (definition == null) file else "$file#$definition"
}

/** One generated `schema/<name>.schema.json`. */
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
