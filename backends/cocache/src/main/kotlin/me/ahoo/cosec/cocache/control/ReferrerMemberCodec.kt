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

package me.ahoo.cosec.cocache.control

import me.ahoo.cocache.api.Codec
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.control.StoredJson.malformed
import me.ahoo.cosec.cocache.snapshot.SnapshotKeyEncoder
import me.ahoo.cosec.control.store.ReferrerEntry
import me.ahoo.cosec.spi.snapshot.SnapshotKeyText

/**
 * One member of a back-reference set (RFC 0007 §5.8): `<docKey>@<revision>`, with `#own` after it when the referrer
 * defines the policy's id itself (`CTL-43`): `tenant:acme@3`, `tenant:acme@4#own`. The revision is digits only, so the
 * last `@` before it ends the document key whatever the key holds. Equal entries have equal bytes.
 */
internal object ReferrerMemberCodec : Codec<ReferrerEntry> {
    private const val WHAT = "back-reference member"
    private const val OWN = "#own"

    override fun encode(value: ReferrerEntry): ByteArray =
        (SnapshotKeyEncoder.encode(value.referrer) + "@" + value.revision + if (value.definesItself) OWN else "")
            .encodeToByteArray()

    /** Anything but that form, a document key that names none, or a revision below 1, is malformed. */
    override fun decode(bytes: ByteArray): ReferrerEntry {
        val text = bytes.decodeToString()
        fun bad(reason: String): Nothing = malformed(WHAT, "[${SnapshotKeyText.printable(text)}] $reason")
        val own = text.endsWith(OWN)
        val body = if (own) text.dropLast(OWN.length) else text
        val at = body.lastIndexOf('@')
        if (at < 0) bad("names no revision")
        val revision = body.substring(at + 1).takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toLongOrNull()
            ?.takeIf { it >= 1 } ?: bad("names no revision")
        val referrer = SnapshotKeyEncoder.decode(body.substring(0, at)) ?: bad("names no document")
        return ReferrerEntry(referrer, revision, own)
    }
}
