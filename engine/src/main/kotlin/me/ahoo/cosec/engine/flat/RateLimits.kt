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
 * A compiled rate limit rule. [id] is the rule's id within the counters: a platform rule's own id, so its Redis
 * buckets are those of 7.x, and `app:<app>/<id>` or `tenant:<tenant>/<id>` for the rules of other documents, so no
 * document counts against another's buckets.
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
        /** Rule ids as 7.x accepts them, which are Redis key segments. */
        private val ID = Regex("[A-Za-z0-9_.-]+")

        /** The bucket of a rule without a key. */
        const val SHARED_BUCKET = "global"

        /** The prefix of a keyed bucket, so a key value literally named `global` is not the shared bucket. */
        const val KEYED_BUCKET_PREFIX = "g:"

        /** The id of [id] within the counters, for a rule of the [kind] document of [owner]. */
        fun counterId(kind: DocumentKind, owner: String, id: String): String = when (kind) {
            DocumentKind.PLATFORM -> id
            DocumentKind.APP -> "app:$owner/$id"
            DocumentKind.TENANT -> "tenant:$owner/$id"
        }

        internal fun isValidId(id: String): Boolean = ID.matches(id)

        /** The types a key can name: the bucket is the value as text. */
        internal val KEY_TYPES = setOf(AttributeType.STRING, AttributeType.IP)

        internal fun policyId(id: String): PolicyId = PolicyId("ratelimit:$id")
    }
}
