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
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.model.Identifiers
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.identity.IdentityFormatException
import me.ahoo.cosec.spi.identity.IdentityFormatException.Reason
import me.ahoo.cosec.spi.identity.IdentityOverflowException
import me.ahoo.cosec.spi.token.TokenSession
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectReader
import java.time.Instant
import java.util.Base64

/**
 * The [Identity] header, format v1: base64url (no padding) of compact JSON with exactly these members, in this order:
 *
 * ```json
 * {"v":1,"tenant":"acme","sub":"alice","scope":{"app":"order","space":""},"roles":["clerk"],
 *  "memberships":{"spaces":{"emea":["auditor"]},"attachments":["suspended"]},
 *  "auth":{"method":"jwt","jti":"0TEC7","exp":1793404800,"iat":1793404200,
 *          "session":{"sid":"0TEC5","auth_time":1793400000,"generation":2}},
 *  "iat":1793401200,"rid":"0TEC7Hw00001zR0","claims":{"name":"Alice"}}
 * ```
 *
 * `memberships` (`IDN-39`) holds what `roles` does not: the principal's roles in each other space (the default space's
 * key is empty; the identity's own space is never a key, and a space names at least one role) and the ids of the
 * policies it attaches, in the order its memberships name them. CoSec's writers write it, empty when there is
 * nothing more (`{"spaces":{},"attachments":[]}`): its presence says the memberships are whole, and a host that decides
 * on the identity refuses one without it. The relay of a request's caller leaves it out when the identity it relays did
 * not state it (`IDN-42`). What a reader takes each member's absence for is recorded in [IdentityMembers].
 *
 * `auth.capped` is `true` for a credential with a role cap (an API key with `roles`, RFC 0009 §4.2), and absent
 * otherwise: the service behind then applies no principal binding either. `auth.iat` is when the credential was issued,
 * absent when it does not say, and never after `auth.exp`. `auth.session` is the session of CoSec's own token
 * (`method` `jwt` only, and only with `auth.iat`): `sid`, `auth_time` (when the user signed in) and a positive
 * `generation`, all three required. `auth_time` and `auth.iat` are not compared: the family's clock wrote the one, the
 * issuing instance's the other, and no rule rests on their order. A service behind the gateway reads the user's login time from it, and a
 * tenant switch issues from it (`IDN-22`); without it, everything else reads as before.
 *
 * Times are epoch seconds, from 0 to the end of year 9999. `claims` may be absent; `auth.exp` may not: a gateway
 * forwards no identity whose credential never expires, and a service refuses one without it. A member this version
 * does not know, at any level, is ignored, unless `crit` names it ([IdentityCrit], `IDN-41`): a later 8.x version may
 * add members an older reader can do without, and names in `crit` those it cannot, which this version refuses (503)
 * rather than read without them. Decoding is otherwise strict: a member of the wrong type, a duplicate, an empty id, a
 * blank tenant, subject or request id (`IDN-38`), an id (or any member other than a claim) with an unpaired UTF-16
 * surrogate (RC1-52), a malformed `crit` or a header over the size limit is refused, never read as anonymous. The
 * `cosec-identity` format descriptor (`schema/8.0/cosec-identity.schema.json`) describes the JSON.
 */
@InternalCoSecApi
object IdentityCodec {
    /** The header the gateway sets on a permitted request, and strips from every request it receives. */
    const val HEADER = "CoSec-Identity"

    const val VERSION = 1

    /**
     * R, the largest header, encoded, a reader accepts by default (`cosec.pipeline.identity.max-size`, `IDN-43`). Never
     * lowered within 8.x: every 8.x writer writes within it, so a reader of any 8.x version reads what any writes.
     */
    const val READ_MAX_BYTES = 8 * 1024

    /**
     * W, the largest header, encoded, CoSec's writers write by default (`cosec.gateway.identity.max-size`, the relay
     * of a request's caller, `IDN-43`). Below [READ_MAX_BYTES] by the room the members a later 8.x version adds may
     * take: a later version may raise it, up to R, never past it.
     */
    const val WRITE_MAX_BYTES = 6 * 1024

    /** The longest string claim value a gateway forwards. */
    const val MAX_CLAIM_LENGTH = 1024

    /**
     * 9999-12-31T23:59:59Z, the last second of year 9999 and the bound of every time the header holds: later seconds are
     * refused, so no time arithmetic on a decoded identity can overflow.
     */
    internal const val MAX_SECONDS = 253_402_300_799L

    /** `auth.kind` and its values (`IDN-44`): what [Identity.Credential.Kind] is on the wire. */
    internal const val KIND = "kind"
    internal const val PERSON = "person"
    internal const val WORKLOAD = "workload"

    private val reader: ObjectReader = CoSecJson.mapper.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    /**
     * The header value of [identity]; [IdentityOverflowException] when it is longer than [maxBytes] or a claim is, and
     * [IdentityFormatException] when [decode] would refuse what it wrote, as [decode] refuses it (#1690): a header written
     * is a header read.
     */
    fun encode(identity: Identity, maxBytes: Int = WRITE_MAX_BYTES): String {
        WrittenIdentity.check(identity)
        IdentityJson.check(identity)
        val header = encoder.encodeToString(CoSecJson.mapper.writeValueAsBytes(IdentityJson(identity)))
        if (header.length > maxBytes) {
            throw Unencodable.tooLarge(
                identity.subject,
                header.length,
                maxBytes,
                identity.roles.size,
                identity.claims.size,
                identity.memberships,
            )
        }
        return header
    }

    /** [header] as an identity; [IdentityFormatException] when it is not one this service reads. */
    @Suppress("ThrowsCount")
    fun decode(header: String, maxBytes: Int = READ_MAX_BYTES): Identity {
        if (header.length > maxBytes) {
            throw IdentityFormatException(
                Reason.OVERSIZE,
                "The identity header is ${header.length} bytes, over $maxBytes."
            )
        }
        val bytes = try {
            decoder.decode(header)
        } catch (error: IllegalArgumentException) {
            throw malformed("not base64url", error)
        }
        if (header.endsWith('=')) {
            throw malformed("padded")
        }
        return decodeJson(bytes)
    }

    /** The JSON of an identity, before base64url: what the format descriptor describes. */
    @Suppress("ThrowsCount")
    fun decodeJson(bytes: ByteArray): Identity {
        val root = try {
            reader.readTree(bytes) ?: throw malformed("empty")
        } catch (error: JacksonException) {
            // Where, never what: Jackson's message and the exception quote the input, which a forged header chooses.
            val at = error.location?.let { " at line ${it.lineNr}, column ${it.columnNr}" }.orEmpty()
            throw malformed("not JSON$at")
        }
        // Members this version does not know are ignored, unless `crit` names one (IDN-41): nothing walks the members
        // to find them, so an identity costs no more to read than before, and less.
        root.objectAt("$")
        val version = root.need("v")
        if (!version.isIntegralNumber || version.asLong() != VERSION.toLong()) {
            throw IdentityFormatException(
                Reason.UNSUPPORTED_VERSION,
                "The identity header is version ${QuotedText.of(version.toString())}, not $VERSION."
            )
        }
        root.get(IdentityCrit.MEMBER)?.let { IdentityCrit.check(it, root) }
        val scope = root.need("scope").also { it.objectAt("$.scope") }
        val space = scope.need("space").text("$.scope.space")
        return Identity(
            tenant = root.need("tenant").name("$.tenant"),
            subject = root.need("sub").name("$.sub"),
            app = scope.need("app").text("$.scope.app"),
            space = space,
            roles = roles(root.need("roles"), "$.roles"),
            credential = credential(root.need("auth")),
            issuedAt = root.need("iat").seconds("$.iat"),
            requestId = root.need("rid").name("$.rid"),
            claims = root.get("claims")?.let(::claims).orEmpty(),
            memberships = root.get("memberships")?.let { IdentityMemberships.read(it, space) },
        )
    }

    /** `auth`: how the principal authenticated, with the issue time and the session when the credential says. */
    @Suppress("ThrowsCount")
    private fun credential(auth: JsonNode): Identity.Credential {
        auth.objectAt("$.auth")
        val method = auth.need("method").text("$.auth.method")
        val expiresAt = (auth.get("exp") ?: throw IdentityFormatException(Reason.UNBOUNDED, "The identity has no auth.exp."))
            .seconds("$.auth.exp")
        val issuedAt = auth.get("iat")?.seconds("$.auth.iat")
        if (issuedAt != null && issuedAt.isAfter(expiresAt)) throw malformed("$.auth.iat is after $.auth.exp")
        return Identity.Credential(
            method = method.takeIf { it.isNotEmpty() }?.let(Identity::Method) ?: throw malformed("$.auth.method is empty"),
            kind = kind(auth.get(KIND), method),
            id = auth.need("jti").id("$.auth.jti"),
            expiresAt = expiresAt,
            capped = auth.get("capped")?.let {
                if (!it.isBoolean || !it.booleanValue()) throw malformed("$.auth.capped is true when present")
                true
            } ?: false,
            issuedAt = issuedAt,
            session = auth.get("session")?.let { session(it, method, issuedAt) },
        )
    }

    /**
     * `auth.kind` (`IDN-44`): `person`, or a workload's when it says anything else or nothing, the restrictive reading;
     * a credential named `api-key` or `service` declared a person's is malformed, since no writer of CoSec writes one.
     */
    private fun kind(node: JsonNode?, method: String): Identity.Credential.Kind {
        node ?: return Identity.Credential.Kind.WORKLOAD
        if (!node.isString) throw malformed("$.auth.kind is not a string")
        if (node.asString() != PERSON) return Identity.Credential.Kind.WORKLOAD
        if (method == Identity.Method.API_KEY.value || method == Identity.Method.SERVICE.value) {
            throw malformed("$.auth.kind is a person's for a workload's credential")
        }
        return Identity.Credential.Kind.PERSON
    }

    /** `auth.session`: CoSec's own token's only, all three members, never read as no session when one is wrong. */
    @Suppress("ThrowsCount")
    private fun session(node: JsonNode, method: String, issuedAt: Instant?): TokenSession {
        node.objectAt("$.auth.session")
        if (method != Identity.Method.JWT.value) throw malformed("$.auth.session is CoSec's own token's only")
        issuedAt ?: throw malformed("$.auth.session needs $.auth.iat")
        val authTime = node.need("auth_time").seconds("$.auth.session.auth_time")
        val generation = node.need("generation")
        if (!generation.isIntegralNumber || !generation.canConvertToLong() || generation.longValue() < 1) {
            throw malformed("$.auth.session.generation is not a positive integer")
        }
        return TokenSession(node.need("sid").id("$.auth.session.sid"), authTime, generation.longValue())
    }

    internal fun roles(node: JsonNode, path: String): Set<String> {
        if (!node.isArray) throw malformed("$path is not an array")
        val roles = LinkedHashSet<String>()
        node.values().forEachIndexed { i, role ->
            if (!roles.add(role.id("$path[$i]"))) throw malformed("$path[$i] repeats a role")
        }
        return roles
    }

    @Suppress("ThrowsCount")
    private fun claims(node: JsonNode): Map<String, Any> {
        if (!node.isObject) throw malformed("$.claims is not an object")
        return node.properties().associateTo(LinkedHashMap()) { (name, value) ->
            if (name.isEmpty()) throw malformed("$.claims has an empty name")
            name to when {
                value.isString -> value.asString().also {
                    if (it.length > MAX_CLAIM_LENGTH) {
                        throw malformed("$.claims.${QuotedText.of(name)} is over $MAX_CLAIM_LENGTH characters")
                    }
                }
                value.isBoolean -> value.booleanValue()
                value.isIntegralNumber -> value.longValue()
                value.isNumber -> value.doubleValue()
                else -> throw malformed("$.claims.${QuotedText.of(name)} is not a string, number or boolean")
            }
        }
    }

    /** That this is an object; its members this version does not know are not looked for (IDN-41). */
    internal fun JsonNode.objectAt(path: String) {
        if (!isObject) throw malformed("$path is not an object")
    }

    private fun JsonNode.need(name: String): JsonNode = get(name) ?: throw malformed("[$name] is missing")

    /** A string field: like every id the identity names, it holds no unpaired UTF-16 surrogate (RC1-52, `IDN-10`). */
    private fun JsonNode.text(path: String): String {
        if (!isString) throw malformed("$path is not a string")
        val text = asString()
        if (!Identifiers.isWellFormed(text)) throw malformed("$path holds an unpaired surrogate")
        return text
    }

    internal fun JsonNode.id(path: String): String = text(path).ifEmpty { throw malformed("$path is empty") }

    /**
     * Who and where: the tenant, the subject and the request. Not blank, empty or white space only (`IDN-38`): a
     * blank subject is no one, and a service would take it as an authenticated principal. Not trimmed: ids are opaque.
     */
    private fun JsonNode.name(path: String): String = id(path).ifBlank { throw malformed("$path is blank") }

    private fun JsonNode.seconds(path: String): Instant {
        if (!isIntegralNumber || !canConvertToLong() || longValue() !in 0..MAX_SECONDS) {
            throw malformed("$path is not epoch seconds up to year 9999")
        }
        return Instant.ofEpochSecond(longValue())
    }

    internal fun malformed(reason: String, cause: Throwable? = null) =
        IdentityFormatException(Reason.MALFORMED, "The identity header is malformed: $reason.", cause)
}
