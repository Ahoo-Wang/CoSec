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

package me.ahoo.cosec.engine.pattern

/**
 * Variable names follow Spring's rule, except that they must not be empty and hold no control character: Java counts
 * the ignorable ones (`\u0000`–`\u0008`, `\u000E`–`\u001B`, `\u007F`–`\u009F`) as identifier parts, and a name is an id the
 * document defines, read back in `resource.var.<name>` and in messages (`DOC-13`).
 */
internal object VariableNames {
    /** Why [name] is not a variable name, or `null` if it is one. */
    fun invalid(name: String): String? {
        if (name.isEmpty()) {
            return "a capture needs a name"
        }
        if (!name[0].isJavaIdentifierStart()) {
            return "a variable name cannot start with '${name[0]}'"
        }
        val illegal = name.firstOrNull { it != '-' && (!it.isJavaIdentifierPart() || it.isISOControl()) } ?: return null
        return "'$illegal' is not allowed in a variable name"
    }
}
