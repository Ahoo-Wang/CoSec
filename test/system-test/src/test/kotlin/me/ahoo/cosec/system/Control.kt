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

package me.ahoo.cosec.system

import me.ahoo.test.asserts.assert
import java.net.URI
import java.nio.file.Files

/**
 * The control API of the login and control-plane host, as [operator] calls it (`wiki/policies/testing.md`): every call is
 * an ordinary request to that host first, decided by its own pipeline, then authorized again by the control plane.
 */
class Control(private val operator: SignedIn) {
    private val base: String = "${SystemTopology.auth}/cosec/control/v1"

    fun document(key: String): Exchange = Http.get(at("/documents/$key"), operator.bearer)

    /** The revision [key]'s head is at. */
    fun revision(key: String): Long = (document(key).ok().json().getValue("revision") as Number).toLong()

    fun propose(key: String, base: Long, operations: List<Map<String, Any?>>, note: String): Exchange =
        Http.post(
            at("/changes"),
            mapOf("document" to key, "base" to base, "note" to note, "operations" to operations),
            operator.bearer
        )

    fun apply(key: String, document: Any, note: String, mode: String = "MERGE"): Exchange =
        Http.post(
            at("/documents/$key:apply"),
            mapOf("document" to document, "mode" to mode, "note" to note),
            operator.bearer
        )

    fun rollback(key: String, revision: Long, note: String): Exchange =
        Http.post(at("/documents/$key:rollback"), mapOf("revision" to revision, "note" to note), operator.bearer)

    fun change(id: String): Exchange = Http.get(at("/changes/$id"), operator.bearer)

    /** A transition of a change: `shadow`, `skip-shadow`, `approve`, `publish`, ... */
    fun step(id: String, verb: String, body: Map<String, Any?> = emptyMap()): Exchange =
        Http.post(at("/changes/$id:$verb"), body, operator.bearer)

    /**
     * Publishes the hand-written document `documents/<file>` as [key], as a trust root seeds what it wrote: an `apply`
     * that only adds, its window skipped, then published. Returns the revision published.
     */
    fun seed(key: String, file: String): Long {
        val document = Json.mapper.readValue(Files.readString(SystemTopology.documents.resolve(file)), Map::class.java)
        val id = apply(key, document, "system test: seed $file").created().id()
        step(id, "skip-shadow", mapOf("reason" to "system test: seeding")).ok()
        val published = step(id, "publish").ok().json()
        published["state"].assert().describedAs("seeding $key: $published").isEqualTo("PUBLISHED")
        return (published.getValue("publishedRevision") as Number).toLong()
    }

    private fun at(path: String): URI = URI.create("$base$path")
}

fun Exchange.ok(): Exchange = also { status.assert().describedAs("expected 200: $this").isEqualTo(200) }

fun Exchange.created(): Exchange = also { status.assert().describedAs("expected 201: $this").isEqualTo(201) }

fun Exchange.id(): String = json().getValue("id") as String
