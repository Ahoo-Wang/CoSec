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

package me.ahoo.cosec.runtime.document

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.flat.RateLimitSource
import me.ahoo.cosec.engine.flat.ResourceSource
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.model.AppId
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.RoleId
import me.ahoo.cosec.model.SpaceId
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.ConditionLimits
import me.ahoo.cosec.model.decision.DocumentKind
import java.time.Instant

/**
 * A platform, app or tenant snapshot document as stored (RFC 0004 §7.1), decoded but not compiled. [PolicyDocumentCodec]
 * reads and writes it; [PolicyDocumentCompiler] turns it into what the engine evaluates.
 *
 * ```jsonc
 * { "schema": 2, "revision": 42, "publishedAt": "2026-11-01T08:00:00Z",
 *   "provenance": { "parent": { "revision": 41, "digest": "9f2c…" }, "change": "0QH8K3…", "kind": "PUBLISH", ... },
 *   "catalog": { "groups": [ ... ] },                        // app documents only
 *   "roles": [ { "id": "clerk", "permissions": ["read"] } ], // app and tenant documents (RFC 0012 §5)
 *   "policies": [ { "id": "deny-ip", "attachable": false, "condition": { ... },
 *                   "statements": [ { "name": "s", "effect": "deny", "action": ["http:*"],
 *                                     "resource": ["http:path:/admin/{page}", { "pattern": "http:path:/a", "caseSensitive": false }],
 *                                     "condition": { ... } } ] } ],
 *   "bindings": [ { "id": "g", "subject": "*", "app": "*", "space": "*", "policy": "deny-ip" },
 *                 { "id": "root", "subject": { "principal": "cosec", "tenant": "(platform)" }, "app": "*", "space": "*",
 *                   "policy": "cosec:root", "breakGlass": true, "expiresAt": "2026-12-31T00:00:00Z" },
 *                 { "id": "r", "subject": { "role": "editor" }, "app": "order", "space": "*", "policy": "perm:order/read" } ] }
 * ```
 */
class PolicyDocument(
    val kind: DocumentKind,
    /** The app id of an app document, the tenant id of a tenant document; empty for the platform. */
    val owner: String,
    val revision: Long,
    val publishedAt: Instant,
    val policies: List<PolicyDefinition>,
    val bindings: List<Binding>,
    /** An app document's catalog; null for the other kinds. */
    val catalog: Catalog? = null,
    /**
     * The platform boundary (RFC 0007 §7.6): in the platform document, the default (`"boundaries": {"default": …}`);
     * in a tenant document, the one that holds that tenant instead (`"boundary": …`). Null for none.
     */
    val boundary: PolicyId? = null,
    /** Where the revision came from; written by the control plane, absent in documents it did not publish. */
    val provenance: Provenance? = null,
    /**
     * The platform's condition limits (RFC 0007 §3.1 N3, RFC 0005 §3.5): what every document compiles against, so the
     * control plane and every instance agree. Null: [ConditionLimits.DEFAULT]. Platform only.
     */
    val limits: ConditionLimits? = null,
    /**
     * The external attributes the platform declares (RFC 0007 §3.1 N3, RFC 0006 §5.4.1): the catalog conditions of every
     * document are checked against, with their types. Null: the instance's providers decide. Platform only.
     */
    val attributes: List<Attribute>? = null,
    /**
     * Its rate limit rules, in order (RFC 0006 §8.1): a permit's obligations. Every kind of document may have them; the
     * rules of an app or a tenant document count in buckets of their own.
     */
    val rateLimits: List<RateLimitSource> = emptyList(),
    /**
     * The roles an app document (system roles) or a tenant document (custom roles) defines (RFC 0012 §5). The stored
     * short form: nothing reads them as grants except through [RoleExpansion.effective].
     */
    val roles: List<RoleDefinition> = emptyList(),
) {
    init {
        require(revision > 0) { "Revision must be positive, was $revision." }
        require((kind == DocumentKind.PLATFORM) == owner.isEmpty()) { "Only the platform document has no owner." }
        require(catalog == null || kind == DocumentKind.APP) { "Only an app document has a catalog." }
        require(boundary == null || kind != DocumentKind.APP) { "An app document names no boundary." }
        require((limits == null && attributes == null) || kind == DocumentKind.PLATFORM) {
            "Only the platform document has limits and attributes."
        }
        require(attributes == null || attributes.all { it.external }) { "Declared attributes are external." }
        roles.firstNotNullOfOrNull { it.shapeProblem(kind) }?.let { throw IllegalArgumentException(it) }
    }
}

/** A policy as a document writes it. */
class PolicyDefinition(
    val id: PolicyId,
    val attachable: Boolean,
    val condition: Condition?,
    val statements: List<StatementSource>,
    /** A platform boundary (RFC 0007 §7.6): only ALLOWs, never bound or attached. */
    val boundary: Boolean = false,
)

/**
 * An app's permission catalog (RFC 0004 §4.9, RFC 0012 §4): for UIs, generators and validation. An entry that writes
 * [CatalogPermission.actions] defines a permission; [RoleExpansion.effective] turns it into a policy, and evaluation
 * reads only that.
 */
class Catalog(val groups: List<CatalogGroup>)

class CatalogGroup(
    val name: String,
    val description: String? = null,
    /**
     * Whether the group's permissions apply in any space a role holding them applies in. A permission written with
     * `action` in a group that is not spaced applies only in the default space (RFC 0012 §4.2). Informational for a
     * hand-written policy, whose author writes that restriction.
     */
    val spaced: Boolean = false,
    val permissions: List<CatalogPermission>,
)

class CatalogPermission(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    /** The action patterns of the permission this entry defines (RFC 0012 §4.1); null when it defines none. */
    val actions: List<String>? = null,
    /** Its resources, which need [actions]; empty for any resource. */
    val resources: List<ResourceSource> = emptyList(),
)

/** What a role grants (RFC 0012 §5). */
sealed interface RolePermissions {
    /** `"*"`: every permission the catalog defines, as it grows. Only in an app document (RFC 0012 §5.1). */
    data object All : RolePermissions

    /** These permissions, by their catalog ids; possibly none. */
    data class Listed(val ids: List<String>) : RolePermissions
}

/**
 * A role of an app (RFC 0012 §5): which permissions it grants. A system role, in its app's document, has neither [app]
 * nor [space]; a custom role, in a tenant document, names its [app] and, optionally, the one [space] it applies in.
 */
class RoleDefinition(
    val id: RoleId,
    val permissions: RolePermissions,
    val name: String? = null,
    val description: String? = null,
    val app: AppId? = null,
    val space: SpaceId? = null,
) {
    /** Why a document of [kind] cannot hold this role; null when it can. */
    @InternalCoSecApi
    fun shapeProblem(kind: DocumentKind): String? = when {
        kind == DocumentKind.PLATFORM -> "role [${id.value}]: the platform document has no roles"
        kind == DocumentKind.APP && (app != null || space != null) ->
            "role [${id.value}]: a system role belongs to its document's app and names no app or space"
        kind == DocumentKind.TENANT && app == null -> "role [${id.value}]: a tenant's role names its app"
        kind == DocumentKind.TENANT && permissions == RolePermissions.All ->
            "role [${id.value}]: \"*\" is only for a system role; a tenant's role lists its permissions"
        else -> null
    }
}

/** The resource pattern options a document writes, as [ResourceSource] holds them. */
internal fun ResourceSource.hasOptions(): Boolean = !caseSensitive || !decodeAndParseSegments
