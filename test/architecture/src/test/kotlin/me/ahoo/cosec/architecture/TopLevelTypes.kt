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

package me.ahoo.cosec.architecture

/**
 * The non-private top-level types a Kotlin source declares: classes (enum, data, value, sealed, annotation), interfaces
 * (fun interfaces too) and objects, read from the declarations at the start of a line, where ktlint puts every
 * top-level declaration. A typealias names a type but declares none, so it is not counted.
 */
object TopLevelTypes {
    private val modifiers = listOf(
        "public", "internal", "private", "protected", "sealed", "data", "abstract", "enum", "open", "annotation",
        "fun", "value", "inline", "expect", "actual", "external",
    ).joinToString("|")
    private val declaration = Regex(
        """^(?:@\w+(?:\([^)]*\))?\s+)*((?:(?:$modifiers)\s+)*)(?:class|interface|object)\s+(\w+)""",
    )

    fun of(source: MainSource): List<String> = source.lines.mapNotNull { line ->
        declaration.find(line)?.takeUnless { "private" in it.groupValues[1].split(Regex("\\s+")) }?.groupValues?.get(2)
    }
}
