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

package me.ahoo.cosec.control.change

/**
 * What one person cannot publish alone (RFC 0007 §7.5), or what the approver is shown with it: [reason] says which
 * ([Reason]). [subject] names the fragment or entry; an approval covers the escalations whose subjects it lists, and
 * needs to cover every one that is not [informational].
 */
class Escalation(val subject: String, val reason: Reason, val detail: String = "") {
    /**
     * Shown to the approver, never itself a reason to approve: the tenants an app change reaches ([Reason.TENANT_BINDINGS],
     * RC1-34), and the role class a change was granted by ([Reason.ROLE_CLASS], `CTL-79`). Their own change after an
     * approval does not send the change back, since what was approved is widening the policy. Nothing else is: an aggregated entry never stands in for one that needs an approval (`CTL-48`).
     */
    val informational: Boolean get() = reason == Reason.TENANT_BINDINGS || reason == Reason.ROLE_CLASS

    // A plain class, not a `data class`: a `ChangeEvent` carries it, so it is stable, and a field it gains is a new
    // constructor (RFC 0010 C4). It compares by value, as the change records it is part of do.
    override fun equals(other: Any?): Boolean =
        this === other || other is Escalation && subject == other.subject && reason == other.reason &&
            detail == other.detail

    override fun hashCode(): Int = (subject.hashCode() * 31 + reason.hashCode()) * 31 + detail.hashCode()

    override fun toString(): String = "Escalation(subject=$subject, reason=$reason, detail=$detail)"

    /**
     * Why: one list, written by name in the change records and the audit events (R2C-5). The grant analysis's reasons
     * are its `UnknownReason`s by the same names.
     */
    enum class Reason {
        /** The author is not in the document's tenant. */
        CROSS_TENANT,

        /** No single ALLOW of the author is proven to contain what the change grants. */
        NOT_COVERED,

        /** A DENY that binds the author may overlap what the change grants. */
        DENY_OVERLAP,

        /** A new or changed binding names a policy no document defines. */
        UNRESOLVED,

        /** An app document the grant reaches could not be read, or the app list cannot be trusted. */
        UNREADABLE,

        /** A grant to `Anyone` in the default tenant, where unauthenticated callers are. */
        ANONYMOUS,

        /** The analysis made all the comparisons its budget allows before it could tell (`CTL-49`). */
        TOO_LARGE,

        /** A platform or app document change, or a tenant boundary change, that only the trust root makes alone. */
        NOT_ANALYZED,

        /** A new or changed break-glass binding: always two people (RFC 0007 §8), recorded before it is approved. */
        BREAK_GLASS,

        /**
         * With [NOT_ANALYZED] on an app document: the tenants whose bindings a widened permission policy reaches, one
         * entry per policy, counted from the back-reference index. Informational.
         */
        TENANT_BINDINGS,

        /**
         * Only in a [me.ahoo.cosec.control.audit.ChangeEvent.Kind.REVIEW_NEEDED] event: the comparison after a publish
         * did not finish in time, so whether anything moved is not known (RC1-33).
         */
        DETECTION_TIMED_OUT,

        /**
         * The role class (`CTL-79`): a change of a tenant's document that only writes or removes roles, by a holder of
         * `cosec:manage-roles` on it, needs no second person on account of who grants. Recorded in place of the
         * escalations it lifted, naming the holder and what was lifted. Informational.
         */
        ROLE_CLASS,

        /** A name this version does not know, read from a record a later one wrote: never informational. */
        UNKNOWN,
        ;

        companion object {
            private val byName = entries.filter { it != UNKNOWN }.associateBy { it.name }

            /** The reason of [name], or [UNKNOWN]. */
            fun of(name: String): Reason = byName[name] ?: UNKNOWN
        }
    }
}
