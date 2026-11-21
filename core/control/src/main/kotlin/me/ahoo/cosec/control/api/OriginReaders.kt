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

package me.ahoo.cosec.control.api

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.ChangeRequest

/**
 * Who is given a change author's whole origin (`CTL-70`): the credential's id, the request's id and the connection's
 * address, besides `asserted` and `via.method`, which every reader of the change gets ([ChangeAnswer]).
 */
@InternalCoSecApi
fun interface OriginReaders {
    /** Whether [operator], who may read [change], is given its author's whole origin. */
    suspend fun whole(operator: Operator, change: ChangeRequest): Boolean

    @InternalCoSecApi
    companion object {
        /** Nobody: the short origin for every reader, as a plane that cannot tell answers. */
        val NONE: OriginReaders = OriginReaders { _, _ -> false }
    }
}
