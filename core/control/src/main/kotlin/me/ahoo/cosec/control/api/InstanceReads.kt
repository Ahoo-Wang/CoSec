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
import me.ahoo.cosec.control.authz.ControlActions
import me.ahoo.cosec.control.authz.Operator
import me.ahoo.cosec.control.change.InstanceReaders
import me.ahoo.cosec.control.lifecycle.ChangeAuthority
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * The readers of the store (`CTL-89`, RFC 0014 R5): every instance that reported in the last hour, with the release it
 * runs. A platform operator's: authorized as a read of the platform document, since it names every instance.
 */
@InternalCoSecApi
class InstanceReads internal constructor(
    private val authority: ChangeAuthority,
    private val readers: suspend () -> InstanceReaders,
) {
    suspend fun read(operator: Operator): InstanceReaders {
        authority.require(operator, SnapshotKey.Platform, ControlActions.READ)
        return readers()
    }
}
