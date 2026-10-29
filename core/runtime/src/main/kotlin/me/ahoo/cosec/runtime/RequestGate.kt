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

package me.ahoo.cosec.runtime

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.http.HttpFacts

/**
 * What the adapters call for every request (RFC 0006 §6.3): an [Outcome] they write, from the facts they read. The
 * [Pipeline] decides with policies; `IdentityGate` reads the identity a gateway verified, for the services behind it.
 * Either way only `StatusMapping` makes the outcome, so the adapters answer alike. Only CoSec implements it: a gate of an
 * application's own would be a second decision path, so implementing it needs the internal API opt-in (RFC 0011 D4).
 * Not a `fun interface`, which a lambda could implement without the opt-in.
 */
@SubclassOptInRequired(InternalCoSecApi::class)
interface RequestGate {
    suspend fun evaluate(facts: HttpFacts): Outcome
}
