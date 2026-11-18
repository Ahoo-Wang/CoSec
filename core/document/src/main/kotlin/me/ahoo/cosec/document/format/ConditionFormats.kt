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
import me.ahoo.cosec.document.format.FormatParts.astCondition
import me.ahoo.cosec.document.format.FormatParts.ref

/** Conditions (RFC 0005 §3.1): the attribute, the operators and the `time` operand. */
internal object ConditionFormats {
    private val attributeExamples = listOf(
        "subject.id", "subject.authenticated", "scope.app", "scope.space", "scope.roles", "resource.var.id",
        "resource.type", "environment.ip", "http.header.x-trace", "http.origin.host.labels", "request.attributes.region",
    )

    private val attribute = StringType(
        examples = attributeExamples,
        description = "An attribute of the catalog (RFC 0005 §3.2), or an external attribute (`ext.<provider>.<name>`) a " +
            "registered AttributeProvider declares (RFC 0006 §5.4.1)."
    )

    private val conditionLiteral = AnyOf(
        listOf(StringType(examples = listOf("admin", "order", "10.0.0.0/8")), BooleanType())
    )

    private fun operator(name: String, operands: FormatType, ignoreCase: Boolean = false, description: String? = null) =
        ObjectType(
            listOf(Field(name, operands, required = true)) +
                if (ignoreCase) listOf(Field("ignoreCase", BooleanType(), description = "ASCII case folding.", default = false)) else emptyList(),
            description = description,
        )

    val condition = AnyOf(
        listOf(
            operator(
                "eq",
                TupleType(
                    listOf(attribute, AnyOf(conditionLiteral.variants + ref(SNAPSHOT_DOCUMENT, "attributeOperand")))
                ),
                ignoreCase = true
            ),
            operator(
                "in",
                TupleType(
                    listOf(
                        StringType(examples = attributeExamples + "admin"),
                        AnyOf(listOf(ArrayType(conditionLiteral), attribute))
                    )
                ),
                description = "`[attribute, [literals]]`: the value is one of them; `[literal, set attribute]`: the set holds it.",
            ),
            operator(
                "prefix",
                TupleType(listOf(attribute, StringType(examples = listOf("ad", "com.example")))),
                ignoreCase = true
            ),
            operator(
                "cidr",
                TupleType(listOf(attribute, ArrayType(StringType(examples = listOf("10.0.0.0/8", "2001:db8::/32")))))
            ),
            operator("time", ref(SNAPSHOT_DOCUMENT, "time")),
            operator("exists", attribute),
            operator("and", ArrayType(astCondition, minItems = 1)),
            operator("or", ArrayType(astCondition, minItems = 1)),
            operator("not", astCondition),
        ),
        description = "A condition (RFC 0005 §3.1): one operator.",
    )

    val attributeOperand = ObjectType(listOf(Field("attr", attribute, required = true)))

    val time = ObjectType(
        listOf(
            Field("notBefore", dateTime("2026-01-01T00:00:00Z", "2026-01-01T08:00:00")),
            Field("notAfter", dateTime("2027-01-01T00:00:00Z", "2027-01-01T08:00:00+08:00")),
            Field("zone", StringType(examples = listOf("UTC", "Asia/Shanghai"))),
            Field("daysOfWeek", ArrayType(StringType(enum = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")))),
            Field("from", timeOfDay("09:00")),
            Field("to", timeOfDay("18:00")),
        ),
    )

    private fun dateTime(vararg examples: String) = StringType(
        pattern = FormatPatterns.DATE_TIME,
        examples = examples.toList(),
        counterExamples = FormatPatterns.DATE_TIME_COUNTER_EXAMPLES,
        description = "An ISO date-time; without an offset it is read in zone.",
    )
}
