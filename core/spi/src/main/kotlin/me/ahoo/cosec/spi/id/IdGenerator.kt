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

package me.ahoo.cosec.spi.id

import me.ahoo.cosec.annotations.CoSecSpi

/**
 * Makes the ids of what CoSec creates: request ids (`CoSec-Request-Id`, the `rid` of `CoSec-Identity`), decision event
 * ids, token ids (`jti`) and control-plane change ids. Without one of the application's, [RandomIdGenerator].
 * `cosec-cosid` gives CoSec CosId 5's ids; any other source of ids plugs in with one line, `IdGenerator { … }`.
 *
 * An id must be unique across every instance that shares the stores, and safe in a URL and in an HTTP header: keep to
 * 1 to 64 characters of `A-Z a-z 0-9 . _ -`, as a client's `CoSec-Request-Id` must. [generate] runs on every request,
 * on the request's thread, an event loop included, before there is an outcome to answer a failure with: it must be
 * cheap, and must never block or throw.
 *
 * An id identifies; it is not a secret. Nothing CoSec relies on needs it to be unpredictable: a token is signed, and a
 * request id only correlates logs and audit events. Never use one where guessing it would grant something.
 */
@CoSecSpi
fun interface IdGenerator {
    /** A new id. */
    fun generate(): String
}
