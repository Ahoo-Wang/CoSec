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

package me.ahoo.cosec.control.grant

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.TenantId

/**
 * The published snapshot the grantor's own rights are read from (RFC 0007 §7.4.2): the documents at [now]. [apps]
 * holds every app document, whether or not a change names its app (RC1-4): an app document binds roles, so the grantor
 * may hold or be denied something in any app.
 */
@InternalCoSecApi
class GrantSnapshot(
    val platform: GrantDocument = GrantDocument.EMPTY,
    val apps: Map<AppId, GrantDocument> = emptyMap(),
    val tenants: Map<TenantId, GrantDocument> = emptyMap(),
    /** Epoch milliseconds: bindings that expired by then do not apply. */
    val now: Long,
    /**
     * The apps whose document could not be read, so what the grantor holds or is denied there is not known (RC1-4). By
     * default [Dimension.Every], every app [apps] does not have: a snapshot that does not say it holds every app document
     * might not. [Dimension.Only] names the apps that failed when [apps] holds every other one. A fragment that reaches
     * one of them is never contained.
     */
    val unreadApps: Dimension<AppId> = Dimension.Every(),
) {
    fun tenant(id: TenantId): GrantDocument = tenants[id] ?: GrantDocument.EMPTY
}
