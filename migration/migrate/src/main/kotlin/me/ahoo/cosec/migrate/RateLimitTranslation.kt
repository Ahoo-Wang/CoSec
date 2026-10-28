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

import me.ahoo.cosec.engine.flat.RateLimitSource
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.migrate.v7.limiter.RateLimitRule
import me.ahoo.cosec.migrate.v7.serialization.V7JsonSerializer
import me.ahoo.cosec.model.decision.RateLimitSpec
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import tools.jackson.databind.JsonNode

/**
 * A 7.x rate limit rule translated (RFC 0005 §7.3): its action and condition as an 8.0 statement, its key as an
 * attribute path. `convert` writes them into the platform document's `rateLimits` (RFC 0006 §8.1), where they keep
 * their ids, so the Redis buckets of 7.x carry on.
 */
class ConvertedRateLimit(
    val id: String,
    val statement: StatementSource,
    /** The attribute whose value names the bucket; null for one bucket. An absent value is the empty bucket, as in 7.x. */
    val key: String?,
    val permitsPerSecond: Double,
    val windowSeconds: Long,
    val strictFailure: Boolean,
) {
    /** As the platform document holds it. */
    fun toSource(): RateLimitSource =
        RateLimitSource(id, statement, key, RateLimitSpec(permitsPerSecond, windowSeconds), strictFailure)
}

/** The rules of the document of [key]: the 7.x rules are global, so the platform's, in their 7.x buckets (§8.1). */
internal fun List<ConvertedRateLimit>.of(key: SnapshotKey): List<RateLimitSource> =
    if (key == SnapshotKey.Platform) map(ConvertedRateLimit::toSource) else emptyList()

/** Translates rate limit rules with the statement translation, reading their conditions in the DENY direction. */
internal class RateLimitTranslation(
    private val statements: StatementTranslator,
    private val reject: (RejectionKind, String, String) -> Unit,
    private val note: (SemanticChange) -> Unit,
) {
    fun translate(entry: Source7x.Entry, tree: JsonNode): ConvertedRateLimit? {
        val decoded = try {
            V7JsonSerializer.treeToValue(tree, RateLimitRule::class.java)
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            reject(
                RejectionKind.UNDECODABLE,
                entry.location,
                "rate limit rule [${entry.id}] cannot be decoded by 7.x: ${error.message}"
            )
            null
        } catch (error: LinkageError) {
            reject(
                RejectionKind.UNDECODABLE,
                entry.location,
                "rate limit rule [${entry.id}] needs a class missing from the classpath (${error.message})"
            )
            null
        }
        // An unmatched or narrowed rule lets requests escape their limit: its condition limits, like a DENY.
        val statement = statements.statement(tree, entry.location, entry.id, direction = true)
        val keyPart = tree.get("key")?.takeIf { it.isString }?.asString()
        val key = keyPart?.let { part ->
            when (part) {
                // A bucket per address: one address spelled two ways shares a bucket, which limits more, not less.
                Parts.REMOTE_IP -> "environment.ip"
                else -> Parts.of(part)?.attribute ?: run {
                    reject(RejectionKind.CONDITION_NOT_TRANSLATED, "${entry.location}.key", Parts.reason(part))
                    return null
                }
            }
        }
        if (decoded == null || statement == null) {
            return null
        }
        note(
            SemanticChange(
                "RATE-LIMIT",
                entry.location,
                "rate limit rule [${entry.id}] translated into the platform document's rateLimits; it is applied as an " +
                    "obligation of a Permit (RFC 0006 §8)",
            ),
        )
        return ConvertedRateLimit(
            entry.id,
            statement,
            key,
            decoded.rateLimit.permitsPerSecond,
            decoded.rateLimit.windowSeconds,
            decoded.strictFailure,
        )
    }
}
