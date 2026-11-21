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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.control.api

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.InvalidControlRequestException
import me.ahoo.cosec.model.TenantId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * The listings' cursor (`CTL-55`, `CTL-68`): a token a client passes back unchanged, the same on every replica. One
 * that does not decode, or belongs to another listing, is a 400, never a listing from the start.
 */
class ListingCursorTest {
    private val fixture = ControlPlaneTest()
    private val http = ControlHttp(fixture.plane)
    private val plato = fixture.engine.security(TenantId.PLATFORM, "plato")

    private fun status(path: String, query: Map<String, String>): Int = runBlocking {
        http.handle(ControlHttpRequest("GET", path, query, null), plato).status
    }

    private fun refused(cursor: ListingCursor, token: String): Boolean = try {
        cursor.decode(token)
        false
    } catch (_: InvalidControlRequestException) {
        true
    }

    @Test
    fun `a token round-trips for its listing, and is no raw position on the wire`() {
        val positions = mapOf(
            ListingCursor.CHANGES_BY_STATE to "tenant:acme#c1",
            ListingCursor.CHANGES_OF_DOCUMENT to "c1",
            ListingCursor.DOCUMENTS to "app:order",
        )
        for ((cursor, position) in positions) {
            val token = cursor.encode(position)
            token.assert().describedAs("$cursor").doesNotContain(position).matches("[A-Za-z0-9_-]+")
            cursor.decode(token).assert().isEqualTo(position)
        }
    }

    @Test
    fun `a malformed or foreign token is refused for every listing`() {
        val state = ListingCursor.CHANGES_BY_STATE
        val notUtf8 = Base64.getUrlEncoder().withoutPadding().encodeToString(byteArrayOf(-1, -2, -3))
        for (token in listOf(
            "tenant:acme#c1", // the raw position, as before tokens
            "!!!",
            notUtf8,
            ListingCursor.DOCUMENTS.encode("tenant:acme"), // another listing's
            ListingCursor.CHANGES_OF_DOCUMENT.encode("c1"),
            Base64.getUrlEncoder().encodeToString("state|nonsense".encodeToByteArray()), // no position of the listing
            Base64.getUrlEncoder().encodeToString("state|tenant:acme#a:b".encodeToByteArray()), // no change id
        )) {
            refused(state, token).assert().describedAs(token).isTrue()
        }
        refused(ListingCursor.DOCUMENTS, ListingCursor.DOCUMENTS.encode("nonsense")).assert().isTrue()
        refused(ListingCursor.CHANGES_OF_DOCUMENT, ListingCursor.CHANGES_OF_DOCUMENT.encode("a:b")).assert().isTrue()
    }

    @Test
    fun `the routes refuse a cursor that is not theirs with a 400, before listing anything`() {
        runBlocking { fixture.memory.keepOpenChanges() }
        val documents = ListingCursor.DOCUMENTS.encode("tenant:acme")
        status("/changes", mapOf("state" to "CHECKED", "after" to documents)).assert().isEqualTo(400)
        status("/changes", mapOf("document" to "tenant:acme", "after" to documents)).assert().isEqualTo(400)
        status("/changes", mapOf("state" to "CHECKED", "after" to "tenant:acme#c1")).assert().isEqualTo(400)
        status("/documents", mapOf("kind" to "tenant", "after" to "tenant:acme")).assert().isEqualTo(400)
        // A document listing's cursor of the other kind is not this listing's either.
        status("/documents", mapOf("kind" to "tenant", "after" to ListingCursor.DOCUMENTS.encode("app:order")))
            .assert().isEqualTo(400)
        // And each listing takes its own.
        status("/documents", mapOf("kind" to "tenant", "after" to documents)).assert().isEqualTo(200)
        status(
            "/changes",
            mapOf("state" to "CHECKED", "after" to ListingCursor.CHANGES_BY_STATE.encode("tenant:acme#c1"))
        )
            .assert().isEqualTo(200)
    }
}
