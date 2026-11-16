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

import me.ahoo.cosec.annotations.InternalCoSecApi

/**
 * What one person cannot publish alone (RFC 0007 §7.5): a granted fragment the grant analysis could not prove contained
 * ([reason] `NOT_COVERED`, `DENY_OVERLAP`, `CROSS_TENANT`, `UNRESOLVED`), a change to a document it does not analyze
 * (`NOT_ANALYZED`), with each tenant binding it widens for an app document (`TENANT_BINDING`, RC1-34), or a
 * break-glass binding (`BREAK_GLASS`, RFC 0007 §8). [subject] names the fragment or entry; an approval covers the
 * escalations whose subjects it lists.
 */
class Escalation(val subject: String, val reason: String, val detail: String = "") {
    /**
     * Shown to the approver, never itself a reason to approve: the tenant bindings an app change widens (RC1-34). Their
     * own change after an approval does not send the change back, since what was approved is widening the policy.
     */
    val informational: Boolean get() = reason == TENANT_BINDING

    // A plain class, not a `data class`: a `ChangeEvent` carries it, so it is stable, and a field it gains is a new
    // constructor (RFC 0010 C4). It compares by value, as the change records it is part of do.
    override fun equals(other: Any?): Boolean =
        this === other || other is Escalation && subject == other.subject && reason == other.reason &&
            detail == other.detail

    override fun hashCode(): Int = (subject.hashCode() * 31 + reason.hashCode()) * 31 + detail.hashCode()

    override fun toString(): String = "Escalation(subject=$subject, reason=$reason, detail=$detail)"

    companion object {
        const val TENANT_BINDING = "TENANT_BINDING"

        /**
         * Only in a [me.ahoo.cosec.control.audit.ChangeEvent.Kind.REVIEW_NEEDED] event: the comparison after a publish
         * did not finish in time, so whether anything moved is not known (RC1-33).
         */
        const val DETECTION_TIMED_OUT = "DETECTION_TIMED_OUT"
    }
}
