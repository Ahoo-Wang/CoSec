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

package me.ahoo.cosec.engine.flat

/**
 * How the engine reads `subject.attributes.<name>` (RFC 0005 §3.2): the value's `toString()`, whatever its type, so
 * a token's JSON `true` reads as `"true"` and `42` as `"42"`.
 *
 * The grant analysis reads a grantor's attributes through this same function (RFC 0007 §7.4.3 `specialize`, RC1-2):
 * an analysis that reads them any other way can count a statement as held that the engine never applies to the
 * grantor.
 */
object SubjectAttributes {
    /** `subject.attributes.<name>` of [attributes], or `null` when the subject has no such attribute. */
    fun string(attributes: Map<String, Any>, name: String): String? = attributes[name]?.toString()
}
