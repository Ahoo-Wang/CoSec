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

package me.ahoo.cosec.spi.token

/**
 * A token whose signature verified but which carries an `act` claim: an RFC 8693 delegation token, where `sub` is the
 * party acted for and `act` the one acting. 8.0 does not support delegation (RFC 0009 §6, `IDN-4`): read as `sub`, such a
 * token would give the actor every permission of the party it acts for. A verifier throws this instead of returning a
 * principal; the authenticator rejects the token as invalid.
 */
class DelegationUnsupportedException(s: String) : TokenVerificationException(s) {
    companion object {
        /** The RFC 8693 actor claim. `may_act` only says who may act for the subject, and does not change it. */
        const val ACTOR_CLAIM: String = "act"
    }
}
