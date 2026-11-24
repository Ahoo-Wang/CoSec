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
import me.ahoo.cosec.model.Identifiers
import me.ahoo.cosec.model.Match
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.identity.IdentityFormatException
import java.time.Instant

/**
 * What [IdentityCodec.decode] would refuse of an identity once written, refused before [IdentityCodec.encode] writes it,
 * at the same path and in the same words (#1690): a header written is a header read. An empty id, a blank tenant,
 * subject or request id (`IDN-38`), an unpaired surrogate, a time outside epoch seconds up to year 9999, an empty claim
 * name. What the types already refuse
 * ([Identity.Credential], [Identity.Method], [me.ahoo.cosec.spi.token.TokenSession]) is not checked again.
 */
internal object WrittenIdentity {
    fun check(identity: Identity) {
        name(identity.tenant, "$.tenant")
        name(identity.subject, "$.sub")
        text(identity.app, "$.scope.app")
        text(identity.space, "$.scope.space")
        roles(identity.roles, "$.roles")
        identity.memberships?.let { memberships(it, identity.space) }
        text(identity.credential.method.value, "$.auth.method")
        id(identity.credential.id, "$.auth.jti")
        identity.credential.expiresAt?.let { seconds(it, "$.auth.exp") }
        identity.credential.issuedAt?.let { seconds(it, "$.auth.iat") }
        identity.credential.session?.let { seconds(it.authTime, "$.auth.session.auth_time") }
        seconds(identity.issuedAt, "$.iat")
        name(identity.requestId, "$.rid")
        if (identity.claims.keys.any { it.isEmpty() }) throw unwritable("$.claims has an empty name")
    }

    /**
     * Each role as an id, in one pass that sorts nothing and builds no path for valid roles: the gateway writes the
     * identity on every forwarded request. The path names the first bad role in the order the header lists them
     * (sorted), as [IdentityCodec.decode] reading it back would.
     */
    private fun roles(roles: Set<String>, path: String) {
        if (roles.all(::writable)) return
        val sorted = roles.sorted()
        val i = sorted.indexOfFirst { !writable(it) }
        id(sorted[i], "$path[$i]")
    }

    /**
     * `memberships` (`IDN-39`), in the order the header lists it: the spaces sorted, then the attachments. Nothing is
     * built for the empty memberships the gateway writes for most principals.
     */
    private fun memberships(memberships: Identity.Memberships, space: String) {
        if (memberships.spaces.isNotEmpty()) {
            for ((name, roles) in memberships.spaces.toSortedMap()) {
                val path = IdentityMemberships.spacePath(name)
                text(name, path)
                if (name == space) throw unwritable("$path is the identity's own space, whose roles are $.roles")
                if (name == Match.WILDCARD) throw unwritable("$path is the wildcard, which is no space")
                if (roles.isEmpty()) throw unwritable("$path names no role")
                roles(roles, path)
            }
        }
        if (memberships.attachments.isEmpty()) return
        val seen = HashSet<String>()
        memberships.attachments.forEachIndexed { i, policy ->
            id(policy, "$.memberships.attachments[$i]")
            if (!seen.add(policy)) throw unwritable("$.memberships.attachments[$i] repeats a policy")
        }
    }

    private fun writable(id: String): Boolean = id.isNotEmpty() && Identifiers.isWellFormed(id)

    private fun text(text: String, path: String) {
        if (!Identifiers.isWellFormed(text)) throw unwritable("$path holds an unpaired surrogate")
    }

    private fun id(id: String, path: String) {
        text(id, path)
        if (id.isEmpty()) throw unwritable("$path is empty")
    }

    /** The tenant, the subject and the request: not blank either (`IDN-38`), as [IdentityCodec.decode] reads them. */
    private fun name(name: String, path: String) {
        id(name, path)
        if (name.isBlank()) throw unwritable("$path is blank")
    }

    private fun seconds(instant: Instant, path: String) {
        if (instant.epochSecond !in 0..IdentityCodec.MAX_SECONDS) {
            throw unwritable("$path is not epoch seconds up to year 9999")
        }
    }

    /** What the decoder says reading it back, refused before it is written: the same exception, reason and words. */
    private fun unwritable(reason: String) =
        IdentityFormatException(IdentityFormatException.Reason.MALFORMED, "The identity header is malformed: $reason.")
}
