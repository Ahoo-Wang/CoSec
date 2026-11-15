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
 * table does not allow is a debt, with the batch that removes it: the list only shrinks.
 */
object PackageLedger {
    const val ROOT = "(root)"

    class Module(
        val name: String,
        val base: String,
        /** Package to the packages it may use. */
        val allowed: Map<String, Set<String>>,
        val debt: List<Edge> = emptyList(),
    )

    class Edge(val from: String, val to: String, val batch: Batch, val why: String)

    private const val SHADOW = "shadow"
    private const val EVENT = "event"
    private const val SNAPSHOT = "snapshot"
    private const val IDENTITY = "identity"
    private const val CHANGE = "change"
    private const val CALL = "call"
    private const val HTTP = "http"

    val MODULES: List<Module> = listOf(
        Module(
            "runtime",
            "me.ahoo.cosec.runtime",
            mapOf(
                ROOT to setOf(
                    "attribute", "audit", "authn", CALL, EVENT, HTTP, IDENTITY, "ratelimit", SHADOW, SNAPSHOT, "token",
                ),
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
                "api" to setOf("audit", "authz", CHANGE, "grant", "store"),
                CHANGE to setOf("audit", "authz", "grant", "store"),
            ),
            listOf(
                Edge(
                    "store",
                    CHANGE,
                    Batch.B4,
                    "the store's records name the change values; they move to a values package"
                ),
                Edge("audit", CHANGE, Batch.B4, "change events carry the change values"),
                Edge("authz", CHANGE, Batch.B4, "the authorizer throws ChangeRejectedException"),
            ),
        ),
        Module(
            "document",
            "me.ahoo.cosec.document",
            mapOf(
                ROOT to setOf("json"),
                "format" to setOf(ROOT),
                IDENTITY to setOf("json"),
                "route" to setOf("json"),
                SHADOW to setOf("json"),
            ),
        ),
    )
}
