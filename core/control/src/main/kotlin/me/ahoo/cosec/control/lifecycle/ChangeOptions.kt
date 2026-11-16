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

@file:OptIn(me.ahoo.cosec.annotations.InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.control.lifecycle

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.PolicyDocumentCompiler

/** The limits of what a change may publish (RFC 0007 §6.2 V2), and how changes expire and are approved. */
@InternalCoSecApi
class ChangeOptions(
    /** RFC 0003 Q14. */
    val maxDocumentBytes: Int = 8 * 1024 * 1024,
    /** RFC 0004 Q2; the compiler enforces it. */
    val principalBindingLimit: Int = PolicyDocumentCompiler.DEFAULT_PRINCIPAL_BINDING_LIMIT,
    /** How long a change may stay unpublished before it expires (`cosec.control.changes.expire-after`). */
    val expireAfter: java.time.Duration = java.time.Duration.ofDays(7),
    /** How long an approval is valid (`cosec.control.approval.expire-after`, RFC 0007 §7.5). */
    val approvalExpireAfter: java.time.Duration = java.time.Duration.ofHours(72),
    /**
     * `cosec.control.approval.four-eyes` (RFC 0007 Q10): the trust root's own changes are analyzed too, and what the
     * analysis cannot prove needs another administrator.
     */
    val fourEyes: Boolean = false,
    /** When a shadow window may close (`cosec.control.shadow.*`, RFC 0007 §6.4). */
    val shadow: ShadowOptions = ShadowOptions(),
    /**
     * The longest a break-glass binding may live from the moment it publishes (`cosec.control.break-glass.max-duration`,
     * RFC 0007 §8): 4 hours by default, never more than 24.
     */
    val breakGlassMaxDuration: java.time.Duration = java.time.Duration.ofHours(4),
) {
    init {
        require(
            maxDocumentBytes > 0 && principalBindingLimit >= 0 && !expireAfter.isNegative && !approvalExpireAfter.isNegative &&
                breakGlassMaxDuration > java.time.Duration.ZERO && breakGlassMaxDuration <= MAX_BREAK_GLASS
        ) { "Invalid change options." }
    }
}
