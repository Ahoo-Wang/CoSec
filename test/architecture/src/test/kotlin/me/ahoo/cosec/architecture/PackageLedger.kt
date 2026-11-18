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

package me.ahoo.cosec.architecture

/**
 * RFC 0013 §3.3 as data: inside a module, which of its packages may use which. A package is the first segment under the
 * module's root package, or [ROOT] for the root package itself. The allowed edges of a module form no cycle, so a
 * module whose code keeps to them has none; [PackageDependencyTest] checks both. An edge the code has today and the
 * table does not allow is a debt, with the batch that removes it: the list only shrinks. A leaf uses no other package
 * of its module, and the test says so of it in so many words, not only by its absence from the table.
 */
object PackageLedger {
    const val ROOT = "(root)"

    class Module(
        val name: String,
        val base: String,
        /** Package to the packages it may use. */
        val allowed: Map<String, Set<String>>,
        val debt: List<Edge> = emptyList(),
        /** Packages that use no other package of the module: the values the others are made of. */
        val leaves: Set<String> = emptySet(),
    )

    class Edge(val from: String, val to: String, val batch: Batch, val why: String)

    private const val SHADOW = "shadow"
    private const val EVENT = "event"
    private const val SNAPSHOT = "snapshot"
    private const val IDENTITY = "identity"
    private const val CHANGE = "change"
    private const val LIFECYCLE = "lifecycle"
    private const val CALL = "call"
    private const val HTTP = "http"
    private const val STAGE = "stage"

    val MODULES: List<Module> = listOf(
        Module(
            "runtime",
            "me.ahoo.cosec.runtime",
            mapOf(
                ROOT to setOf(
                    "attribute", "audit", "authn", CALL, EVENT, HTTP, IDENTITY, "ratelimit", SHADOW, SNAPSHOT, STAGE,
                    "token",
                ),
                STAGE to setOf("attribute", CALL, EVENT, HTTP, IDENTITY, "ratelimit", SHADOW, SNAPSHOT),
                "audit" to setOf(EVENT, HTTP),
                "authn" to setOf("token"),
                EVENT to setOf(CALL, HTTP, SNAPSHOT),
                IDENTITY to setOf(CALL, HTTP),
                SHADOW to setOf(EVENT, SNAPSHOT),
                SNAPSHOT to setOf("attribute"),
            ),
        ),
        Module(
            "control",
            "me.ahoo.cosec.control",
            mapOf(
                "api" to setOf("audit", "authz", CHANGE, "grant", LIFECYCLE, "store"),
                LIFECYCLE to setOf("audit", "authz", CHANGE, "grant", "store"),
                // The store's records, the change events and the authorizer's refusals are made of the change values.
                "store" to setOf(CHANGE),
                "audit" to setOf(CHANGE),
                "authz" to setOf(CHANGE),
            ),
            // The change values reach nothing in control: what acts on them is in lifecycle (RFC 0013 §7.1, B4).
            leaves = setOf(CHANGE),
        ),
        Module(
            "document",
            "me.ahoo.cosec.document",
            mapOf(
                ROOT to setOf("json"),
                "format" to setOf(ROOT),
                IDENTITY to setOf("json"),
                "route" to setOf("json"),
                // A candidate's document is made by SnapshotDocuments (SNP-12); the root does not reach shadow.
                SHADOW to setOf("json", ROOT),
            ),
        ),
    )
}
