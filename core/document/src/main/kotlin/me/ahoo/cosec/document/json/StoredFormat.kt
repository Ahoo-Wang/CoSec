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

package me.ahoo.cosec.document.json

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * The reader's rule for CoSec's stored records (R2C-10, R2C-28, `CTL-53`, `IDN-31`, `SNP-27`): each carries
 * `"format": <n>`.
 * - A record without one was written before formats, and is format 1.
 * - Of a format the reader knows, it reads the members it knows and ignores the others; a member it does not find
 *   takes its documented default ([StoredMembers]).
 * - A member its `crit` names that the reader does not know refuses that one record ([StoredCrit]): it is neither read
 *   nor rewritten, so a member an older instance must not ignore or drop is never lost to it.
 * - A higher format is refused, as a [SnapshotFormatException]: never read as absent.
 *
 * A format number is kept for a change no reader of the major version can follow (`SNP-29`): within 8.x, a session is
 * format 2 at most and every other record format 1, and a member is added within a format, with `crit` when ignoring or
 * dropping it would widen anything. A refusal by this rule carries [Later] as its cause when a later version wrote the
 * record, and is counted ([StoredRefusals]). The policy documents (`schema`), the API keys and the back-reference members
 * are decision inputs and stay strict.
 */
@InternalCoSecApi
object StoredFormat {
    const val FIELD: String = "format"

    /** Writes [format] into [node] as its format member. */
    fun write(node: ObjectNode, format: Int = 1): ObjectNode = node.put(FIELD, format)

    /**
     * The format of [node], a stored [what] of [members]: 1 without one; refused above [known], or for a `crit` member it
     * does not know ([StoredCrit]); each refusal counted ([StoredRefusals]).
     */
    fun read(node: JsonNode, members: StoredMembers, what: String, known: Int = 1): Int {
        try {
            val format = of(node, what, known)
            StoredCrit.check(node, members, what)
            return format
        } catch (refused: SnapshotFormatException) {
            val reason = if (refused.cause is Later) StoredRefusals.Reason.LATER else StoredRefusals.Reason.MALFORMED
            StoredRefusals.record(members.record, reason)
            throw refused
        }
    }

    /**
     * The cause of a refusal of a record a later version wrote: its [record] kind, and the [members] its `crit` names
     * that this version does not know (none for a later format). No stack: an expected answer during a rolling upgrade.
     */
    @InternalCoSecApi
    class Later(val record: StoredRecord?, val members: List<String>) : RuntimeException(null, null, false, false)

    /** The [Later] in [error]'s causes: a record a later version wrote, not a store that is broken; null otherwise. */
    fun later(error: Throwable): Later? =
        generateSequence(error) { it.cause }.take(CAUSE_DEPTH).firstNotNullOfOrNull { it as? Later }

    private const val CAUSE_DEPTH = 8

    /** The format of [node], a stored [what]: 1 without one; refused above [known]. */
    private fun of(node: JsonNode, what: String, known: Int): Int {
        val format = if (node.isObject) node.get(FIELD) else null
        val read = format?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.intValue()
        val refused = when {
            !node.isObject -> "The $what is not an object."
            format == null -> return 1
            read == null || read < 1 -> "The $what has a format that is not a positive integer."
            read > known -> throw SnapshotFormatException(
                "The $what is of format $read, which a later CoSec wrote; this version reads format $known and before.",
                Later(null, emptyList()),
            )
            else -> return read
        }
        throw SnapshotFormatException(refused)
    }
}
