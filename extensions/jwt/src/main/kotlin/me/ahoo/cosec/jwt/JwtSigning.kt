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

package me.ahoo.cosec.jwt

import com.auth0.jwt.algorithms.Algorithm
import me.ahoo.cosec.annotations.InternalCoSecApi

/**
 * The key CoSec's tokens are signed with and verified by: what [JwtTokenConverter] signs with and [JwtTokenVerifier]
 * checks against, so that the JWT library CoSec uses stays out of its public signatures and can change without
 * breaking an application. Built by a factory named for the algorithm; a symmetric key is a secret of at least as many
 * UTF-8 bytes as the hash it feeds (RFC 7518 §3.2: 32, 48, 64), which the starter checks for `cosec.jwt.secret`.
 *
 * The same secret signs and verifies: a service that verifies tokens holds a key that could issue them
 * (`wiki/operations/topologies.md`).
 */
class JwtSigning private constructor(algorithm: Algorithm) {
    /** The library's algorithm, for CoSec's own modules. */
    @InternalCoSecApi
    val algorithm: Algorithm = algorithm

    init {
        Jwts.requireSigning(algorithm)
    }

    override fun toString(): String = "JwtSigning(${algorithm.name})"

    companion object {
        /** HMAC-SHA256 over [secret] (`cosec.jwt.algorithm: hmac256`, the default). */
        fun hmac256(secret: String): JwtSigning = JwtSigning(Algorithm.HMAC256(secret))

        /** HMAC-SHA384 over [secret]. */
        fun hmac384(secret: String): JwtSigning = JwtSigning(Algorithm.HMAC384(secret))

        /** HMAC-SHA512 over [secret]. */
        fun hmac512(secret: String): JwtSigning = JwtSigning(Algorithm.HMAC512(secret))

        /** Any signing algorithm of the JWT library (an RSA or EC key pair, say): CoSec's own modules and tests only. */
        @InternalCoSecApi
        fun of(algorithm: Algorithm): JwtSigning = JwtSigning(algorithm)
    }
}
