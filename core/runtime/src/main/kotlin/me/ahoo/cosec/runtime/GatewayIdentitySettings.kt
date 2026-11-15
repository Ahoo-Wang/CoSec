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

package me.ahoo.cosec.runtime

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.identity.IdentityCodec
import me.ahoo.cosec.runtime.identity.IdentityGate
import me.ahoo.cosec.spi.id.IdGenerator
import me.ahoo.cosec.spi.id.RandomIdGenerator
import java.time.Clock
import java.time.Duration

/**
 * Behind a CoSec gateway: what a service accepts of the identity the gateway forwards (RFC 0009 §5), set in
 * [Pipeline.behindGateway]. The properties are the starter's `cosec.pipeline.identity.*`, with the same defaults.
 */
@ExperimentalCoSecApi
class GatewayIdentitySettings internal constructor() {
    /** The largest identity accepted, in bytes (`max-size`). */
    var maxBytes: Int = IdentityCodec.DEFAULT_MAX_BYTES

    /** How old a forwarded identity may be (`max-age`). */
    var maxAge: Duration = IdentityGate.DEFAULT_MAX_AGE

    /** How far the gateway's clock may be ahead of this service's (`cosec.authentication.revocation.skew`). */
    var skew: Duration = IdentityGate.DEFAULT_SKEW

    var clock: Clock = Clock.systemUTC()

    /** The ids of requests without a plain `CoSec-Request-Id` header. */
    var ids: IdGenerator = RandomIdGenerator
}
