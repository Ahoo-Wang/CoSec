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

package me.ahoo.cosec.model.scope

import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.TenantId

/**
 * A checked scope (RFC 0004 §4.1). Only [ScopeResolver] constructs one (RFC 0001 P4): the constructor is internal to
 * this module, and `ScopeConstructionTest` checks that nothing else in it calls the constructor. The declared scope a
 * client asks for is `me.ahoo.cosec.model.Scope`.
 */
class Scope internal constructor(
    val tenant: TenantId,
    val app: AppId,
    val appSource: AppSource,
    val space: SpaceId,
    /** The roles the principal has in [space], in claim order. */
    val roles: List<RoleId>,
) {
    enum class AppSource { HOST, DECLARED }
}
