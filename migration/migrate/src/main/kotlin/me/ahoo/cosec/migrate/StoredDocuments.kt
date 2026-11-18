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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.migrate

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.store.ControlStore
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SubjectRef
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/** [PublishedDocuments] as the heads in [store] are, each document read once. A document that cannot be read fails. */
class StoredDocuments(private val store: ControlStore) : PublishedDocuments {
    private val platform: PolicyDocument? by lazy { read(SnapshotKey.Platform) }
    private val tenants = HashMap<TenantId, PolicyDocument?>()
    private val all: List<PolicyDocument> by lazy {
        runBlocking { store.documents() }.mapNotNull { read(it) }
    }

    override fun attachable(tenant: TenantId, policy: PolicyId): Boolean {
        val own = tenant(tenant)?.policies?.firstOrNull { it.id == policy }
        val shared = platform?.policies?.firstOrNull { it.id == policy }
        if (own != null && shared != null &&
            PolicyDocumentCodec.policyTree(own) != PolicyDocumentCodec.policyTree(shared)
        ) {
            return false
        }
        return (own ?: shared)?.attachable == true
    }

    override fun principalGrants(tenant: TenantId, principal: String): List<String> =
        listOfNotNull(platform, tenant(tenant)).flatMap { document ->
            document.bindings.filter { it.names(tenant, principal) }.map { "${describe(document)}/${it.id.value}" }
        }

    override fun boundRole(role: RoleId): Boolean =
        all.any { document -> document.bindings.any { (it.subject as? SubjectRef.Role)?.id == role } }

    private fun Binding.names(tenant: TenantId, principal: String): Boolean =
        (subject as? SubjectRef.Principal)?.let { it.tenant == tenant && it.id.value == principal } == true

    private fun describe(document: PolicyDocument): String =
        if (document.kind == DocumentKind.PLATFORM) "platform" else "${document.kind.name.lowercase()}:${document.owner}"

    private fun tenant(tenant: TenantId): PolicyDocument? = tenants.getOrPut(tenant) {
        read(SnapshotKey.Tenant(tenant.value))
    }

    private fun read(key: SnapshotKey): PolicyDocument? {
        val stored = runBlocking { store.head(key) } ?: return null
        val (kind, owner) = when (key) {
            SnapshotKey.Platform -> DocumentKind.PLATFORM to ""
            is SnapshotKey.App -> DocumentKind.APP to key.appId
            is SnapshotKey.Tenant -> DocumentKind.TENANT to key.tenantId
        }
        return PolicyDocumentCodec.decode(kind, owner, stored.document.bodyBytes())
    }
}
