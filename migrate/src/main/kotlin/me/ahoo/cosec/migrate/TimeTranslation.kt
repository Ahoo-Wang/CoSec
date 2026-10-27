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

package me.ahoo.cosec.migrate

import me.ahoo.cosec.model.condition.Condition
import tools.jackson.databind.JsonNode
import java.time.DayOfWeek

/**
 * The 7.x `time` condition (RFC 0005 §7.1): the same configuration, read against `environment.time` (§2 J5). Days of
 * the week are written in full, after 7.x's reading of them (trimmed, any case, full name or first three letters).
 */
internal object TimeTranslation {
    private val FIELDS = setOf("notBefore", "notAfter", "zone", "daysOfWeek", "from", "to", ConditionTranslator.NEGATE)

    fun translate(config: JsonNode, location: String, reject: (RejectionKind, String, String) -> Unit): Translated? {
        val unknown = if (config.isObject) config.propertyNames().filterNot { it in FIELDS } else listOf("(not an object)")
        if (unknown.isNotEmpty()) {
            reject(RejectionKind.UNDECODABLE, location, "a time condition has no field $unknown")
            return null
        }
        fun text(key: String): String? = config.get(key)?.takeIf { it.isString }?.asString()
        val days = config.get("daysOfWeek")?.let { node ->
            node.values().map { day ->
                val upper = day.asString().trim().uppercase()
                DayOfWeek.entries.firstOrNull { it.name == upper || it.name.take(3) == upper }?.name ?: run {
                    reject(RejectionKind.UNDECODABLE, location, "[${day.asString()}] is not a day of the week")
                    return null
                }
            }
        }
        return Translated.Ast(
            Condition.Time(
                notBefore = text("notBefore"),
                notAfter = text("notAfter"),
                zone = text("zone"),
                daysOfWeek = days,
                from = text("from"),
                to = text("to"),
            ),
        )
    }
}
