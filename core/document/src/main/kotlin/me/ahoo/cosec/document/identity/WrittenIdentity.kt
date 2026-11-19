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
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.identity.IdentityFormatException
import java.time.Instant

/**
 * What [IdentityCodec.decode] would refuse of an identity once written, refused before [IdentityCodec.encode] writes it,
 * at the same path and in the same words (#1690): a header written is a header read. An empty id, an unpaired
 * surrogate, a time outside epoch seconds up to year 9999, an empty claim name. What the types already refuse
 * ([Identity.Credential], [Identity.Method], [me.ahoo.cosec.spi.token.TokenSession]) is not checked again.
 */
internal object WrittenIdentity {
    fun check(identity: Identity) {
        id(identity.tenant, "$.tenant")
        id(identity.subject, "$.sub")
        text(identity.app, "$.scope.app")
        text(identity.space, "$.scope.space")
        identity.roles.sorted().forEachIndexed { i, role -> id(role, "$.roles[$i]") }
        text(identity.credential.method.value, "$.auth.method")
        id(identity.credential.id, "$.auth.jti")
        identity.credential.expiresAt?.let { seconds(it, "$.auth.exp") }
        identity.credential.issuedAt?.let { seconds(it, "$.auth.iat") }
        identity.credential.session?.let { seconds(it.authTime, "$.auth.session.auth_time") }
        seconds(identity.issuedAt, "$.iat")
        id(identity.requestId, "$.rid")
        if (identity.claims.keys.any { it.isEmpty() }) throw unwritable("$.claims has an empty name")
    }

    private fun text(text: String, path: String) {
        if (!Identifiers.isWellFormed(text)) throw unwritable("$path holds an unpaired surrogate")
    }

    private fun id(id: String, path: String) {
        text(id, path)
        if (id.isEmpty()) throw unwritable("$path is empty")
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
