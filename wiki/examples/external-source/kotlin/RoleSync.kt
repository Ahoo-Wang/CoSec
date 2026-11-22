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

@file:OptIn(ExperimentalCoSecApi::class)

package com.example.sync

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.control.sdk.Catalog
import me.ahoo.cosec.control.sdk.Change
import me.ahoo.cosec.control.sdk.ChangeCheck
import me.ahoo.cosec.control.sdk.ChangeRequest
import me.ahoo.cosec.control.sdk.ControlCaller
import me.ahoo.cosec.control.sdk.ControlClient
import me.ahoo.cosec.control.sdk.Operation
import me.ahoo.cosec.control.sdk.Permissions
import me.ahoo.cosec.control.sdk.Role
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/** A role as the identity system holds it: your own type. */
class SourceRole(val id: String, val app: String, val name: String, val permissions: List<String>)

/**
 * The sync service: each event of the identity system becomes one change, written through the control plane as the
 * service's own principal, `sync-service` of the platform tenant. The same code runs in a host that embeds the control
 * plane and in one that calls a control-plane host: [control] is over whichever `ControlService` the starter provides.
 */
class RoleSync(private val control: ControlClient) {
    private val caller = ControlCaller.service("sync-service")

    /** A role of [tenant] created or changed: by the role class, it publishes with no person. */
    suspend fun roleChanged(tenant: String, role: SourceRole, event: Long): Change =
        write(
            SnapshotKey.Tenant(tenant),
            Operation.PutRole(Role(role.id, Permissions.Of(role.permissions), app = role.app, name = role.name)),
            "${role.id} changed in the identity system (event $event)",
        )

    /** A role of [tenant] deleted: a removal grants nothing, so it publishes with no person. */
    suspend fun roleDeleted(tenant: String, role: String, event: Long): Change =
        write(SnapshotKey.Tenant(tenant), Operation.RemoveRole(role), "$role deleted in the identity system (event $event)")

    /**
     * An app's permissions changed: what a role of every tenant may hold, so it waits for a person, who approves it on
     * the operators' routes. The answer is that waiting change: report its id.
     */
    suspend fun catalogChanged(app: String, catalog: Catalog, event: Long): Change =
        write(SnapshotKey.App(app), Operation.PutCatalog(catalog), "$app published its permissions (event $event)")

    /** What writing [role] into [tenant] would answer, with nothing stored: for a dry run of the projection. */
    suspend fun wouldWrite(tenant: String, role: SourceRole): ChangeCheck {
        val key = SnapshotKey.Tenant(tenant)
        val head = control.document(caller, key).revision
        val put = Operation.PutRole(Role(role.id, Permissions.Of(role.permissions), app = role.app, name = role.name))
        return control.check(caller, ChangeRequest(key, head, listOf(put), "a dry run"))
    }

    private suspend fun write(document: SnapshotKey, operation: Operation, note: String): Change =
        control.proposeAndPublishWithoutShadow(
            caller,
            document,
            listOf(operation),
            note = note,
            skipReason = "projected from the identity system, which owns the roles",
        )
}
