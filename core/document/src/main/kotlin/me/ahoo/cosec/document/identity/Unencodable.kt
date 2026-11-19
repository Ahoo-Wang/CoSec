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
import me.ahoo.cosec.spi.identity.IdentityOverflowException

/**
 * Why [IdentityCodec.encode] cannot write an identity, built out of line: the gateway encodes on every forwarded request,
 * and messages built in place make the methods of that path too large for the JIT to inline (#1691: `encode` of a large
 * identity lost 4 % and gained an allocation). Names are quoted as [QuotedText] quotes them.
 */
internal object Unencodable {
    fun tooLarge(subject: String, length: Int, maxBytes: Int, roles: Int, claims: Int) = IdentityOverflowException(
        "The identity of [${QuotedText.of(subject)}] is $length bytes encoded, over the limit of $maxBytes: " +
            "$roles roles, $claims claims."
    )

    fun neverExpires(subject: String) = IdentityOverflowException(
        "The credential of [${QuotedText.of(subject)}] never expires: a service could not bound the identity."
    )

    fun claimTooLong(name: String, length: Int) = IdentityOverflowException(
        "Claim [${QuotedText.of(name)}] is $length characters, over ${IdentityCodec.MAX_CLAIM_LENGTH}."
    )

    fun claimType(name: String, value: Any) = IdentityOverflowException(
        "Claim [${QuotedText.of(name)}] is a ${value::class.simpleName}, not a string, number or boolean."
    )

    fun claimInexact(name: String, value: Number) = IdentityOverflowException(
        "Claim [${QuotedText.of(name)}] is ${value::class.simpleName} ${QuotedText.of(value.toString())}, " +
            "which the identity header cannot hold exactly."
    )
}
