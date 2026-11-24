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
import me.ahoo.cosec.model.Identifiers
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import tools.jackson.databind.JsonNode

/**
 * `crit`, the members of a stored record a reader must understand (`SNP-27`), in the shape of the identity header's: the
 * paths from the record's root of members a later 8.x version adds whose being ignored, or dropped when an older reader
 * rewrites the record, would widen anything ([StoredMembers]). A reader ignores any member it does not know, unless this
 * list names it; then it refuses that one record, never reads or rewrites it. CoSec's writers of 8.0 write no `crit`.
 *
 * A list that is not one is malformed: not an array; an entry that is not a path of member names joined by `.`; an entry
 * repeated; more than [MAX_ENTRIES] entries; or an entry this version knows that the record does not hold. An empty list
 * is no list. A path through an array names that member of each element; a path into a map whose keys are data is no
 * member: unknown, as a later member is.
 */
internal object StoredCrit {
    const val MEMBER = "crit"

    /** The most entries a list holds: far more than members are ever added in one major version. */
    const val MAX_ENTRIES = 16

    private val PATH = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)*")

    /** Checks the `crit` of [root], a [members] record: refuses what is malformed, then what names a member it lacks. */
    // stays: each malformed shape is its own refusal, as the identity header's list is
    @Suppress("ThrowsCount")
    fun check(root: JsonNode, members: StoredMembers, what: String) {
        val node = root.get(MEMBER) ?: return
        if (!node.isArray) throw malformed(what, "its $MEMBER is not an array")
        if (node.size() > MAX_ENTRIES) throw malformed(what, "its $MEMBER names more than $MAX_ENTRIES members")
        val seen = HashSet<String>(node.size() * 2)
        var unknown: MutableList<String>? = null
        for (index in 0 until node.size()) {
            val entry = node.get(index)
            val path = entry.takeIf { it.isString }?.stringValue()
            if (path == null || !PATH.matches(path)) throw malformed(what, "its $MEMBER[$index] is not the path of a member")
            if (!seen.add(path)) throw malformed(what, "its $MEMBER[$index] repeats a member")
            when {
                path !in members.byPath -> (unknown ?: ArrayList<String>().also { unknown = it }).add(path)
                !holds(root, path.split('.'), 0) -> throw malformed(what, "its $MEMBER[$index] names a member it does not hold")
            }
        }
        unknown?.let { paths ->
            throw SnapshotFormatException(
                "A stored $what names members in $MEMBER that this version does not understand: " +
                    "${paths.joinToString { "[${Identifiers.printable(it)}]" }}; a later CoSec wrote it.",
                StoredFormat.Later(members.record, paths),
            )
        }
    }

    /** Whether [node] holds the member [names] from [at] name, an array's elements each tried. */
    private fun holds(node: JsonNode?, names: List<String>, at: Int): Boolean = when {
        node == null -> false
        at == names.size -> true
        node.isArray -> (0 until node.size()).any { holds(node.get(it), names, at) }
        else -> holds(node.get(names[at]), names, at + 1)
    }

    private fun malformed(what: String, reason: String) = SnapshotFormatException("A stored $what is malformed: $reason.")
}
