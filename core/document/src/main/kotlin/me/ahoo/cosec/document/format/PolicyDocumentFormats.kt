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

package me.ahoo.cosec.document.format

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.format.BindingFormats.platformBinding
import me.ahoo.cosec.document.format.DocumentFormats.BINDING
import me.ahoo.cosec.document.format.DocumentFormats.CATALOG
import me.ahoo.cosec.document.format.DocumentFormats.SNAPSHOT_DOCUMENT
import me.ahoo.cosec.document.format.FormatParts.ref
import me.ahoo.cosec.document.format.PlatformFormats.platformSections
import me.ahoo.cosec.document.format.RateLimitFormats.rateLimits
import me.ahoo.cosec.document.format.RoleFormats.appRole
import me.ahoo.cosec.document.format.RoleFormats.tenantRole

/**
 * The 8.0 formats (RFC 0004 §7.1, RFC 0005 §3.1, §5.1): bindings, catalogs, and the definitions of `schema: 2`
 * platform, app and tenant documents, which [DocumentFormats] puts into `cosec-snapshot-document`.
 */
internal object PolicyDocumentFormats {
    val bindingSchema: SchemaFile = BindingFormats.bindingSchema

    val catalogSchema: SchemaFile = CatalogFormats.catalogSchema

    private val documentHeader = listOf(
        Field("schema", IntegerType(const = 2), required = true, description = "The document format version."),
        Field("revision", IntegerType(minimum = 1), required = true),
        Field(
            "publishedAt",
            StringType(format = "date-time", examples = listOf("2026-10-26T00:00:00Z")),
            required = true
        ),
        Field(
            "provenance",
            ref(SNAPSHOT_DOCUMENT, "provenance"),
            description = "Where the revision came from (RFC 0007 §5.3); the control plane writes it on every publish.",
        ),
    )

    private val documentSections = listOf(
        Field("policies", ArrayType(ref(SNAPSHOT_DOCUMENT, "policy"))),
        Field("bindings", ArrayType(ref(BINDING))),
        rateLimits,
    )

    val documentDefinitions: Map<String, FormatType> = mapOf(
        "platformDocument" to ObjectType(
            documentHeader + Field(
                "boundaries",
                ObjectType(
                    listOf(
                        Field(
                            "default",
                            StringType(minLength = 1, examples = listOf("boundary-standard")),
                            description = "The boundary policy that holds every tenant that names none (RFC 0007 §7.6).",
                        ),
                    ),
                ),
            ) + platformSections + listOf(
                Field("policies", ArrayType(ref(SNAPSHOT_DOCUMENT, "policy"))),
                Field("bindings", ArrayType(ref(SNAPSHOT_DOCUMENT, "platformBinding"))),
                rateLimits,
            ),
            description = "The platform document (RFC 0004 §7.1) with what only it holds: boundaries, and the limits " +
                "and attributes every document compiles against (RFC 0007 §7.6, §3.1 N3). A principal names its tenant.",
        ),
        "tenantDocument" to ObjectType(
            documentHeader + Field(
                "boundary",
                StringType(minLength = 1, examples = listOf("boundary-restricted")),
                description = "The platform boundary policy that holds this tenant instead of the default; only the platform writes it.",
            ) + documentSections + Field("roles", ArrayType(ref(SNAPSHOT_DOCUMENT, "tenantRole"))),
            description = "A tenant document (RFC 0004 §7.1): the tenant's own policies, bindings and custom roles.",
        ),
        "tenantRole" to tenantRole,
        "appRole" to appRole,
        "platformBinding" to platformBinding(),
        "appDocument" to ObjectType(
            documentHeader + Field("catalog", ref(CATALOG)) + documentSections +
                Field("roles", ArrayType(ref(SNAPSHOT_DOCUMENT, "appRole"))),
            description = "An app document: its catalog, its permission policies `perm:<app>/<permission>`, its system " +
                "roles and role bindings.",
        ),
        "provenance" to ProvenanceFormats.provenance,
        "policy" to PolicyFormats.policy,
        "statement" to PolicyFormats.statement,
        "rateLimit" to RateLimitFormats.rateLimit,
        "actionPattern" to PolicyFormats.actionPattern,
        "resource" to PolicyFormats.resource,
        "resourcePattern" to PolicyFormats.resourcePattern,
        "condition" to ConditionFormats.condition,
        "attributeOperand" to ConditionFormats.attributeOperand,
        "time" to ConditionFormats.time,
    )
}
