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

/**
 * At a gateway: what the pipeline forwards to the services behind it in `CoSec-Identity` (RFC 0009 §5), set in
 * [PipelineBuilder.forwardIdentity]. The properties are the starter's `cosec.gateway.identity.*`.
 */
@ExperimentalCoSecApi
class IdentityForwardingSettings internal constructor() {
    /** The token claims forwarded, strings, numbers and booleans only (`claims`). */
    var claims: Set<String> = emptySet()

    /** The largest identity forwarded, in bytes (`max-size`): a larger one fails its request (500). */
    var maxBytes: Int = IdentityCodec.DEFAULT_MAX_BYTES
}
