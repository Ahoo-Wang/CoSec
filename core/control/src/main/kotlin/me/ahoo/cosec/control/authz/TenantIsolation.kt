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

package me.ahoo.cosec.control.authz

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * The rule that never goes through policy (RFC 0007 §7.2, TNT-3): a tenant's principal acts only on its own tenant's
 * document; the platform and app documents are the platform tenant's alone. It is the last line when a policy is
 * written wrongly, so it is checked before any policy is.
 */
internal object TenantIsolation {
    fun allows(operator: Operator, key: SnapshotKey): Boolean = when (key) {
        is SnapshotKey.Tenant -> operator.platform || key.tenantId == operator.tenant.value
        else -> operator.platform
    }
}
