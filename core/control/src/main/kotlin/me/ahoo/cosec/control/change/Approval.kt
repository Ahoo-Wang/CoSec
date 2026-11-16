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

package me.ahoo.cosec.control.change

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.model.Memberships
import java.time.Instant

/**
 * An approval (RFC 0007 §7.5): by [approver], at [at], of the change whose candidate digest was [candidate], covering
 * the escalation subjects [covers]. It is valid for `cosec.control.approval.expire-after` from [at], and only while
 * the approver, with the [memberships] and [attributes] they approved with, still holds `cosec:approve` on the
 * document: publishing checks that again (review K3). An approval without them cannot be checked, so it does not count.
 */
@ExperimentalCoSecApi
data class Approval(
    val approver: Actor,
    val at: Instant,
    val candidate: String,
    val covers: List<String>,
    val memberships: Memberships? = null,
    /**
     * The approver's token attributes as authenticated, of every JSON type (round-2 review R2): strings, numbers,
     * booleans, lists and objects, so that the check at publish reads what the approval was decided on.
     */
    val attributes: Map<String, Any> = emptyMap(),
    /**
     * Whether the approver's principal bindings reached it when it approved: false with a capped credential (RFC 0009
     * §4.2). Publish checks the approver again under the same terms, so a capped key never counts with more.
     */
    val principalBindings: Boolean = true,
    /** Whether break-glass could reach the approver when it approved: only with CoSec's own token. */
    val breakGlass: Boolean = true,
    /**
     * The credential the approver approved with (its token's `jti`) and when it was issued (`iat`): publishing checks
     * that neither that token nor the approver's tokens issued by then have been revoked since (RC1-11). Null when the
     * credential did not say; a revocation of the approver's tokens then counts.
     */
    val credentialId: String? = null,
    val issuedAt: Instant? = null,
)
