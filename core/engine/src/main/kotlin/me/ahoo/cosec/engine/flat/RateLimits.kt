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

import me.ahoo.cosec.model.Identifiers
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.model.decision.RateLimitSpec

/**
 * A rate limit rule as a document writes it (RFC 0006 §8.1): the requests it limits, as one statement (its action,
 * resource and condition; its effect is irrelevant), the attribute that splits it into buckets, and its quota.
 */
class RateLimitSource(
    val id: String,
    val statement: StatementSource,
    /** The attribute path whose value names the bucket; null for one bucket shared by every matching request. */
    val key: String?,
    val limit: RateLimitSpec,
    /** Whether a backend that cannot count is a refusal (503) instead of skipping the rule. */
    val strictFailure: Boolean = false,
)

/**
 * A compiled rate limit rule. [id] is the rule's id within the counters, its document and its own id ([counterId]), so
 * no document counts against another's buckets.
 */
class CompiledRateLimit internal constructor(
    val id: String,
    internal val policy: CompiledPolicy,
    internal val statement: CompiledStatement,
    internal val key: Attribute?,
    val limit: RateLimitSpec,
    val strictFailure: Boolean,
) {
    /** The external attributes it reads: the statement's and the key's. */
    internal val externals: Array<Attribute> =
        (statement.externals.toList() + listOfNotNull(key?.takeIf { it.external })).toTypedArray()

    override fun toString(): String = "CompiledRateLimit($id)"

    companion object {
        /** Rule ids are Redis key segments; without `:` or `/`, no rule can name another document's counter. */
        private val ID = Regex("[A-Za-z0-9_.-]+")

        /** The bucket of a rule without a key. */
        const val SHARED_BUCKET = "global"

        /** The prefix of a keyed bucket, so a key value literally named `global` is not the shared bucket. */
        const val KEYED_BUCKET_PREFIX = "k:"

        /** The prefix of the bucket of a key value with an unpaired UTF-16 surrogate, escaped ([keyedBucket]). */
        const val ESCAPED_BUCKET_PREFIX = "e:"

        /**
         * The bucket of the key value [value]: [KEYED_BUCKET_PREFIX] and the value. A value with an unpaired UTF-16
         * surrogate has no UTF-8 encoding, and a limiter writes its counters' keys as UTF-8: Spring's template would
         * hold `?` in its place, and the value would count in another value's bucket (RC1-52). Its bucket is
         * [ESCAPED_BUCKET_PREFIX] and the value with each `\` doubled and each unpaired surrogate as its `\uXXXX`
         * escape: well formed, and its own.
         */
        fun keyedBucket(value: String): String {
            if (Identifiers.isWellFormed(value)) {
                return KEYED_BUCKET_PREFIX + value
            }
            val bucket = StringBuilder(ESCAPED_BUCKET_PREFIX.length + value.length + ESCAPE_LENGTH)
            bucket.append(ESCAPED_BUCKET_PREFIX)
            for (index in value.indices) {
                val c = value[index]
                when {
                    c == '\\' -> bucket.append("\\\\")
                    Identifiers.isUnpairedSurrogateAt(value, index) ->
                        bucket.append("\\u").append(c.code.toString(HEX).uppercase().padStart(ESCAPE_DIGITS, '0'))
                    else -> bucket.append(c)
                }
            }
            return bucket.toString()
        }

        private const val HEX = 16
        private const val ESCAPE_DIGITS = 4
        private const val ESCAPE_LENGTH = 6

        /** The id of [id] within the counters, for a rule of the [kind] document of [owner]: `<document>/<id>`. */
        fun counterId(kind: DocumentKind, owner: String, id: String): String = when (kind) {
            DocumentKind.PLATFORM -> "platform/$id"
            DocumentKind.APP -> "app:$owner/$id"
            DocumentKind.TENANT -> "tenant:$owner/$id"
        }

        internal fun isValidId(id: String): Boolean = ID.matches(id)

        /** The types a key can name: the bucket is the value as text. */
        internal val KEY_TYPES = setOf(AttributeType.STRING, AttributeType.IP)

        internal fun policyId(id: String): PolicyId = PolicyId("ratelimit:$id")
    }
}
