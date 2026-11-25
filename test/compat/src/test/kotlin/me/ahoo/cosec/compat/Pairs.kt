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

package me.ahoo.cosec.compat

import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A writer and a reader of a format: across the two versions both ways, and within the current one. */
val PAIRS: List<Pair<Side, Side>> = listOf(
    Side.CURRENT to Side.PREVIOUS,
    Side.PREVIOUS to Side.CURRENT,
    Side.CURRENT to Side.CURRENT,
)

/** A key prefix of its own for one case, so cases never read one another's keys. */
fun prefix(): String = "compat:" + UUID.randomUUID()

/** The JSON of a `CoSec-Identity` header, and the header of a JSON. */
object HeaderJson {
    fun of(header: String): String = Base64.getUrlDecoder().decode(header).decodeToString()

    fun header(json: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(json.encodeToByteArray())

    /** [json] with the top-level members [members] (each `"name":value`) added first. */
    fun adding(json: String, vararg members: String): String {
        check(json.startsWith("{")) { "not a JSON object: [${json.take(120)}]" }
        return "{" + members.joinToString("") { "$it," } + json.substring(1)
    }

    /**
     * [json] whose `auth.kind` (`IDN-44`) is [value], a JSON value as written (`"robot"`, `1`), or absent when [value]
     * is null, as a writer that does not state it, or states what no version knows, would send.
     */
    fun withKind(json: String, value: String?): String = Claims.withMember(json, "kind", value)
}

/** A JSON object's one string member [name], set to another value or removed: what a later or foreign writer sends. */
private object Claims {
    fun withMember(json: String, name: String, value: String?): String {
        val member = Regex(""""${Regex.escape(name)}":"[^"]*"""")
        check(member.findAll(json).count() == 1) { "not one string member $name in [${json.take(200)}]" }
        return if (value != null) {
            member.replace(json, Regex.escapeReplacement(""""$name":$value"""))
        } else {
            json.replace(Regex(""","${Regex.escape(name)}":"[^"]*"|"${Regex.escape(name)}":"[^"]*",?"""), "")
        }
    }
}

/** A token's JWS header, changed as a later issuer would and signed again (HS256, as the sides issue): JDK types only. */
object Jws {
    private val decoder = Base64.getUrlDecoder()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    /** [token] whose protected header marks the parameter `cosec.later` critical (RFC 7515 §4.1.11, `IDN-47`). */
    fun withCrit(token: String, secret: String): String {
        val (header, payload) = token.split('.')
        val json = decoder.decode(header).decodeToString()
        check(json.startsWith("{")) { "not a JSON header: [$json]" }
        val changed = encoder.encodeToString(
            ("{\"crit\":[\"cosec.later\"],\"cosec.later\":true," + json.substring(1)).encodeToByteArray()
        )
        return signed(changed, payload, secret)
    }

    /**
     * [token] whose `cosec.kind` claim (`IDN-45`) is [value], a JSON value as written (`"robot"`, `1`), or absent when
     * [value] is null: a token of an earlier preview, or one a later issuer declares a kind no version knows.
     */
    fun withKind(token: String, secret: String, value: String?): String {
        val (header, payload) = token.split('.')
        val json = Claims.withMember(decoder.decode(payload).decodeToString(), "cosec.kind", value)
        return signed(header, encoder.encodeToString(json.encodeToByteArray()), secret)
    }

    private fun signed(header: String, payload: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.encodeToByteArray(), "HmacSHA256")) }
        val signature = encoder.encodeToString(mac.doFinal("$header.$payload".encodeToByteArray()))
        return "$header.$payload.$signature"
    }
}
