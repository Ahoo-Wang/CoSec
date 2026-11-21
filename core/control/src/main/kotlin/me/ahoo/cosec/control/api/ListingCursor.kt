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

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.change.ChangeId
import me.ahoo.cosec.control.change.ChangeRequestCodec
import me.ahoo.cosec.control.change.InvalidControlRequestException
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * The cursor of a listing's `next`, passed back as `after` (`CTL-55`, `CTL-68`): one convention for `GET /changes` and
 * `GET /documents`, pinned by `contract/control/cursor.json`. A token is the base64url, without padding, of the listing
 * it belongs to and the store's position in it. It is opaque so that no client learns to read positions out of it, not
 * to hide them: it carries no secret and no server state, so any replica continues from it, and a caller who decodes it
 * reads the position.
 *
 * A token that does not decode, belongs to another listing, or holds no position that listing has is a 400, never a
 * listing from the start.
 */
internal enum class ListingCursor(private val tag: String) {
    /** `GET /changes?state=`: `<document key>#<change id>`, where a bounded scan stopped or the page's last change. */
    CHANGES_BY_STATE("state") {
        override fun valid(position: String): Boolean = position.indices.filter { position[it] == '#' }.any { at ->
            val key = keyOrNull(position.substring(0, at))
            key != null && key.toString() == position.substring(0, at) && idOrNull(position.substring(at + 1)) != null
        }
    },

    /** `GET /changes?document=`: the id of the page's last change. */
    CHANGES_OF_DOCUMENT("document") {
        override fun valid(position: String): Boolean = idOrNull(position) != null
    },

    /** `GET /documents?kind=`: the key of the page's last document. */
    DOCUMENTS("documents") {
        override fun valid(position: String): Boolean = keyOrNull(position)?.toString() == position
    };

    protected abstract fun valid(position: String): Boolean

    fun encode(position: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("$tag$SEPARATOR$position".encodeToByteArray())

    /** The position [token] holds for this listing; a 400 when it holds none. */
    fun decode(token: String): String {
        val text = try {
            utf8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(token)))
                .toString()
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: CharacterCodingException) {
            null
        }
        val position = text?.takeIf { it.startsWith("$tag$SEPARATOR") }?.substring(tag.length + 1)
        if (position == null || !valid(position)) {
            throw InvalidControlRequestException(
                "[after] is not a cursor of this listing: pass back its `next` unchanged."
            )
        }
        return position
    }

    private companion object {
        const val SEPARATOR = '|'
        val utf8 = Charsets.UTF_8

        fun keyOrNull(text: String): SnapshotKey? = try {
            ChangeRequestCodec.documentKeyOf(text)
        } catch (_: SnapshotFormatException) {
            null
        }

        fun idOrNull(text: String): ChangeId? = try {
            ChangeId(text)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
