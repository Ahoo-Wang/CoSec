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

package me.ahoo.cosec.spi.membership

import me.ahoo.cosec.model.CoSecException

/**
 * The memberships of a principal could not be established: its token asks for a [MembershipProvider] and there is
 * none, the provider failed or did not answer in time, or the token's membership claims are not usable. Authorization
 * fails closed (RFC 0004 §4.5.2).
 */
class MembershipUnavailableException(message: String, cause: Throwable? = null) : CoSecException(message, cause)
