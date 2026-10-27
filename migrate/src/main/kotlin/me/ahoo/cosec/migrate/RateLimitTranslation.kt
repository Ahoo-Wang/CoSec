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

import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.limiter.RateLimitRule
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.serialization.CoSecJsonSerializer
import me.ahoo.cosec.snapshot.document.PolicyDefinition
import me.ahoo.cosec.snapshot.document.PolicyDocument
import me.ahoo.cosec.snapshot.document.PolicyDocumentCodec
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.time.Instant

/**
 * A 7.x rate limit rule translated (RFC 0005 §7.3): its action and condition as an 8.0 statement, its key as an
 * attribute path. Rules move into the platform document's `rateLimits` with RFC 0006 PR-5 (§8.1); until then
 * `convert` writes them to `rate-limits.json`.
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
    /** `{"id", "action", "resource", "condition", "key", "permitsPerSecond", "windowSeconds", "strictFailure"}`. */
    fun toJson(): ObjectNode {
        // The statement as the document codec writes it: one format for actions, resources and conditions.
        val document = PolicyDocument(
            DocumentKind.PLATFORM,
            "",
            1,
            Instant.EPOCH,
            listOf(PolicyDefinition(PolicyId(id), attachable = false, condition = null, listOf(statement))),
            emptyList(),
        )
        val written = PolicyDocumentCodec.tree(document).get("policies").get(0).get("statements").get(0) as ObjectNode
        val node = CoSecJsonSerializer.createObjectNode().put("id", id)
        listOf("action", "resource", "condition").forEach { field -> written.get(field)?.let { node.set(field, it) } }
        key?.let { node.put("key", it) }
        return node.put(
            "permitsPerSecond",
            permitsPerSecond
        ).put("windowSeconds", windowSeconds).put("strictFailure", strictFailure)
    }
}

/** Translates rate limit rules with the statement translation, reading their conditions in the DENY direction. */
internal class RateLimitTranslation(
    private val statements: StatementTranslator,
    private val reject: (RejectionKind, String, String) -> Unit,
    private val note: (SemanticChange) -> Unit,
) {
    fun translate(entry: Source7x.Entry, tree: JsonNode): ConvertedRateLimit? {
        val decoded = try {
            CoSecJsonSerializer.treeToValue(tree, RateLimitRule::class.java)
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
                "rate limit rule [${entry.id}] translated into rate-limits.json; it moves into the platform document " +
                    "with RFC 0006 PR-5 and is applied as an obligation of a Permit",
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
