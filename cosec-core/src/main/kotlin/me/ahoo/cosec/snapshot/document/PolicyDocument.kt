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

package me.ahoo.cosec.snapshot.document

import me.ahoo.cosec.engine.flat.ResourceSource
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.model.Binding
import me.ahoo.cosec.model.PolicyId
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

/** An app's permission catalog (RFC 0004 §4.9): for UIs, generators and validation. It takes no part in evaluation. */
class Catalog(val groups: List<CatalogGroup>)

class CatalogGroup(
    val name: String,
    val description: String? = null,
    /** Whether the group's permissions are granted per space; informational, as in 7.x. */
    val spaced: Boolean = false,
    val permissions: List<CatalogPermission>,
)

class CatalogPermission(val id: String, val name: String? = null, val description: String? = null)

/** The resource pattern options a document writes, as [ResourceSource] holds them. */
internal fun ResourceSource.hasOptions(): Boolean = !caseSensitive || !decodeAndParseSegments
