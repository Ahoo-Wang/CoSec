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

package me.ahoo.cosec.document.identity

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.identity.IdentityFormatException
import me.ahoo.cosec.spi.identity.IdentityFormatException.Reason
import tools.jackson.databind.JsonNode

/**
 * `crit`, the members of an identity header a reader must understand (`IDN-41`): the paths from the root of members a
 * later 8.x version adds whose being ignored would widen what a reader decides ([IdentityMembers]). A reader ignores
 * any member it does not know, unless this list names it; then it refuses the header, `503`, since it cannot decide
 * as the writer meant and the deployment still runs a reader older than the writer counted on. CoSec's writers of 8.0
 * write no `crit`: every member they write is one every 8.x reader knows.
 *
 * A list that is not one is malformed (`400`): not an array; an entry that is not a non-empty string of member names
 * joined by `.`, with no unpaired surrogate; an entry repeated; more than [MAX_ENTRIES] entries; or an entry this
 * version knows that the header does not hold. An empty list is no list. A path into a map whose keys are data (a
 * claim, a space) is no member: unknown, as a later member is.
 */
internal object IdentityCrit {
    const val MEMBER = "crit"

    /** The most entries a list holds: far more than members are ever added in one major version. */
    const val MAX_ENTRIES = 16

    private val PATH = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*")

    /** Checks [node], the `crit` of [root]: refuses what is malformed, then what names a member this version lacks. */
    @Suppress("ThrowsCount")
    fun check(node: JsonNode, root: JsonNode) = with(IdentityCodec) {
        if (!node.isArray) throw malformed("$.crit is not an array")
        if (node.size() > MAX_ENTRIES) throw malformed("$.crit names more than $MAX_ENTRIES members")
        if (node.isEmpty) return@with
        val seen = HashSet<String>(node.size() * 2)
        var unknown: MutableList<String>? = null
        node.values().forEachIndexed { i, entry ->
            val path = entry.id("$.crit[$i]")
            if (!PATH.matches(path)) throw malformed("$.crit[$i] is not the path of a member")
            if (!seen.add(path)) throw malformed("$.crit[$i] repeats a member")
            when {
                path !in IdentityMembers.BY_PATH -> (unknown ?: ArrayList<String>().also { unknown = it }).add(path)
                !holds(root, path) -> throw malformed("$.crit[$i] names a member the header does not hold")
            }
        }
        unknown?.let {
            throw IdentityFormatException(
                Reason.UNSUPPORTED_MEMBER,
                "The identity header names members in $.crit that this service does not understand: " +
                    "${QuotedText.names(it)}; it was written by a later version than this service.",
            )
        }
    }

    private fun holds(root: JsonNode, path: String): Boolean =
        path.split('.').fold<String, JsonNode?>(root) { node, name -> node?.get(name) } != null
}
