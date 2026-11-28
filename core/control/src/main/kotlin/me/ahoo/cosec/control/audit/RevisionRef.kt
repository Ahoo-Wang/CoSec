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

package me.ahoo.cosec.control.audit

/**
 * A revision as an event names it: its number and the digest it is addressed by, or a candidate's digest. Stable with
 * [ChangeEvent] (RFC 0010 C4): a plain class, so a member it gains in 8.x is a new constructor.
 */
class RevisionRef(val revision: Long, val digest: String?) {
    override fun equals(other: Any?): Boolean =
        this === other || other is RevisionRef && revision == other.revision && digest == other.digest

    override fun hashCode(): Int = revision.hashCode() * 31 + (digest?.hashCode() ?: 0)

    override fun toString(): String = "RevisionRef(revision=$revision, digest=$digest)"
}
