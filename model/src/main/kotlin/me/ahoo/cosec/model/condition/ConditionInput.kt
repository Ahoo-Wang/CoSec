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

package me.ahoo.cosec.model.condition

/**
 * What a condition reads (RFC 0005 §3.3). The host builds one per request from `AuthzRequest`.
 *
 * Every read is a field read or a hash lookup, and should not allocate. An attribute that is absent from the request
 * reads as `null` (or [NO_TIME]); it is not an error (§3.4). A read throws only when the request holds the attribute
 * but no single value of it (a header sent twice with different values): the statement reading it then errs, which
 * makes a DENY indeterminate and an ALLOW no match.
 *
 * Each method is called only with attributes of its type. [AttributeType.LABELS] attributes are never read: conditions
 * derive them from the host attribute they belong to.
 */
interface ConditionInput {
    /** A [AttributeType.STRING] attribute. Header names arrive in ASCII lower case. */
    fun string(attribute: Attribute): String?

    /** A [AttributeType.BOOL] attribute. */
    fun bool(attribute: Attribute): Boolean?

    /** A [AttributeType.STRING_SET] attribute. */
    fun stringSet(attribute: Attribute): Set<String>?

    /** An [AttributeType.IP] attribute, parsed when the request is (see [IpAddress.parse]). */
    fun ip(attribute: Attribute): IpAddress?

    /** An [AttributeType.INSTANT] attribute in epoch milliseconds, or [NO_TIME]. */
    fun time(attribute: Attribute): Long

    companion object {
        /** The value of an absent [AttributeType.INSTANT] attribute. */
        const val NO_TIME: Long = Long.MIN_VALUE
    }
}
