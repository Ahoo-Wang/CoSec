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

package me.ahoo.cosec.control.lifecycle

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.authz.ControlResources
import me.ahoo.cosec.control.change.ChangeRejectedException
import me.ahoo.cosec.control.lifecycle.ChangeAuthority.Companion.FORBIDDEN
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * The resources of the control plane a change targets (RFC 0007 §7.1): an entry, a section, a boundary, the scope a
 * binding grants in. A target that names no resource is a 403.
 */
internal object ControlTargets {
    fun entry(key: SnapshotKey, kind: String, id: String): Resource = try {
        ControlResources.entry(key, kind, id)
    } catch (e: IllegalArgumentException) {
        throw ChangeRejectedException(FORBIDDEN, e.message.orEmpty(), e)
    }

    fun section(key: SnapshotKey, name: String): Resource = entry(key, name, "")

    /** `doc/tenant/<t>/boundary` for a tenant's boundary, `doc/platform` for the default (RFC 0007 §7.1). */
    fun boundaryOf(key: SnapshotKey): Resource =
        if (key is SnapshotKey.Platform) ControlResources.document(key) else section(key, "boundary")

    /** The scope a binding of [key] grants in: its own tenant's for a tenant document, its subject's otherwise. */
    fun scopeOf(key: SnapshotKey, binding: Binding): Resource {
        val tenant = when (key) {
            is SnapshotKey.Tenant -> TenantId(key.tenantId)
            else -> when (val subject = binding.subject) {
                is SubjectRef.Principal -> subject.tenant
                is SubjectRef.Role -> subject.tenant
                SubjectRef.Anyone -> null
            }
        }
        return try {
            ControlResources.scope(tenant, binding.scope.app, binding.scope.space)
        } catch (e: IllegalArgumentException) {
            throw ChangeRejectedException(FORBIDDEN, e.message.orEmpty(), e)
        }
    }
}
