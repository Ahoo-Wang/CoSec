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

package me.ahoo.cosec.document.format

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.DocumentCompileException
import me.ahoo.cosec.document.PolicyDocumentCompiler
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * The 8.0 half of the counter-example check (`SchemaConformanceTest` decodes 8.0 documents but cannot compile random
 * ones): every value an 8.0 string's descriptor rules out, put into a platform document that otherwise compiles, does
 * not compile, and every example does.
 */
class PolicyDocumentValuesTest {
    private val compiler = PolicyDocumentCompiler()
    private val definitions = PolicyDocumentFormats.documentDefinitions
    private val time = definitions.getValue("time") as ObjectType

    /** A value placed into a statement, or (with [header]) into the document's header fields. */
    private class Placement(
        val name: String,
        val type: StringType,
        val header: (String) -> String = { "" },
        /** The roles of a tenant document, where a value is a role's: such a value is compiled there instead. */
        val roles: ((String) -> String)? = null,
        /** A whole document, where the value is neither a statement's nor a header's: `DOC-13`'s ids. */
        val document: ((String) -> Pair<SnapshotKey, String>)? = null,
        val statement: (String) -> String = { """{"effect":"allow","action":"*"}""" },
    )

    private val provenance = definitions.getValue("provenance") as ObjectType
    private val parent = requireNotNull(provenance.field("parent")).type as ObjectType
    private val platformDocument = definitions.getValue("platformDocument") as ObjectType
    private val attribute = (requireNotNull(platformDocument.field("attributes")).type as ArrayType).items as ObjectType
    private val rateLimit = definitions.getValue("rateLimit") as ObjectType
    private val tenantRole = definitions.getValue("tenantRole") as ObjectType

    private val placements = listOf(
        Placement("actionPattern", definitions.getValue("actionPattern") as StringType) {
            statement(action = it)
        },
        Placement("resourcePattern", definitions.getValue("resourcePattern") as StringType) {
            statement(action = if (it.startsWith("http:path:")) "http:GET" else "order:read", resource = it)
        },
        Placement("time.notBefore", time.string("notBefore")) { statement(time = mapOf("notBefore" to it)) },
        Placement("time.notAfter", time.string("notAfter")) { statement(time = mapOf("notAfter" to it)) },
        Placement("time.from", time.string("from")) { statement(time = mapOf("from" to it, "to" to "23:59")) },
        Placement("time.to", time.string("to")) { statement(time = mapOf("from" to "00:00", "to" to it)) },
        Placement("provenance.parent.digest", parent.string("digest"), header = {
            """"provenance":{"parent":{"revision":1,"digest":${json(it)}},"change":"c","kind":"PUBLISH",""" +
                """"publishedBy":{"tenant":"t","principal":"p"}},"""
        }),
        Placement("attributes.path", attribute.string("path"), header = {
            """"attributes":[{"path":${json(it)},"type":"string"}],"""
        }),
        Placement("rateLimits.id", rateLimit.string("id"), header = {
            """"rateLimits":[{"id":${json(it)},"action":"*","permitsPerSecond":1}],"""
        }),
        Placement(
            "role.id",
            tenantRole.string("id"),
            roles = { """[{"id":${json(it)},"app":"wms","permissions":[]}]""" }
        ),
        Placement("role.space", tenantRole.string("space"), roles = {
            """[{"id":"r","app":"wms","space":${json(it)},"permissions":[]}]"""
        }),
        Placement("role.app", tenantRole.string("app"), roles = { """[{"id":"r","app":${json(it)},"permissions":[]}]""" }),
        Placement("role.permissions", tenantRole.items("permissions"), roles = {
            """[{"id":"r","app":"wms","permissions":[${json(it)}]}]"""
        }),
    ) + idPlacements() + bindingPlacements()

    /** `DOC-13`: every id a document writes outside its bindings, each in a document where its examples compile. */
    private fun idPlacements(): List<Placement> {
        val policy = definitions.getValue("policy") as ObjectType
        val statement = definitions.getValue("statement") as ObjectType
        val permission = PolicyDocumentFormats.catalogSchema.definitions.getValue("permission") as ObjectType
        val boundaries = requireNotNull(platformDocument.field("boundaries")).type as ObjectType
        val tenantDocument = definitions.getValue("tenantDocument") as ObjectType
        return listOf(
            Placement("policy.id", policy.string("id"), document = {
                val policies = """[{"id":${json(it)},"statements":[$ALLOW]}]"""
                if (it.startsWith(PERMISSION)) app(policies = policies) else platform(policies = policies)
            }),
            Placement("statement.name", statement.string("name"), document = {
                platform(
                    policies = """[{"id":"p","statements":[{"name":${json(it)},"effect":"allow","action":"*"}]}]"""
                )
            }),
            Placement("catalog.permission.id", permission.string("id"), document = {
                app(catalog = """"catalog":{"groups":[{"name":"g","permissions":[{"id":${json(it)}}]}]},""")
            }),
            Placement("boundaries.default", boundaries.string("default"), document = {
                platform(
                    policies = """[{"id":${json(it)},"boundary":true,"statements":[$ALLOW]}]""",
                    header = """"boundaries":{"default":${json(it)}},""",
                )
            }),
            Placement("tenant.boundary", tenantDocument.string("boundary"), document = {
                SnapshotKey.Tenant("acme") to document(""""boundary":${json(it)},"policies":[],"bindings":[]""")
            }),
            Placement("rateLimits.key", rateLimit.string("key"), header = {
                """"rateLimits":[{"id":"r","action":"order:read","resource":"order/{orderId}","key":${json(it)},""" +
                    """"permitsPerSecond":1}],"""
            }),
        )
    }

    /** `DOC-13`: every id a binding writes, in the binding schema and in the platform document's variant of it. */
    private fun bindingPlacements(): List<Placement> {
        val binding = PolicyDocumentFormats.bindingSchema.root as ObjectType
        val subjects = PolicyDocumentFormats.bindingSchema.definitions
        val principal = subjects.getValue("principalSubject") as ObjectType
        val role = subjects.getValue("roleSubject") as ObjectType
        val platformSubject = requireNotNull((definitions.getValue("platformBinding") as ObjectType).field("subject"))
        val platformPrincipal = (platformSubject.type as AnyOf).variants.filterIsInstance<ObjectType>().single()
        fun placed(name: String, type: StringType, written: (String) -> String) =
            Placement(name, type, document = { platform(bindings = listOf(written(it))) })
        fun subject(name: String, type: StringType, subject: (String) -> String) =
            placed(name, type) { binding(subject = subject(it)) }

        // A role is granted in an app document.
        fun roleBinding(
            name: String,
            type: StringType,
            subject: (String) -> String
        ) = Placement(name, type, document = {
            app(bindings = "[${binding(subject = subject(it), app = "order-service", policy = "shared")}]")
        })
        return listOf(
            placed("binding.id", binding.string("id")) { binding(id = it) },
            placed("binding.app", binding.string("app")) { binding(app = it) },
            placed("binding.space", binding.string("space")) { binding(space = it) },
            Placement("binding.policy", binding.string("policy"), document = ::boundTo),
            subject("principalSubject.principal", principal.string("principal")) { principal(it, "(platform)") },
            subject("principalSubject.tenant", principal.string("tenant")) { principal("u", it) },
            subject("platformBinding.principal", platformPrincipal.string("principal")) { principal(it, "(platform)") },
            subject("platformBinding.tenant", platformPrincipal.string("tenant")) { principal("u", it) },
            roleBinding("roleSubject.role", role.string("role")) { """{"role":${json(it)}}""" },
            roleBinding("roleSubject.tenant", role.string("tenant")) { """{"role":"r","tenant":${json(it)}}""" },
        )
    }

    /** A binding to [policy], in the document that may bind it: an app's permission, the break-glass root, or one defined here. */
    private fun boundTo(policy: String): Pair<SnapshotKey, String> = when {
        policy.startsWith(PERMISSION) -> app(
            policies = """[{"id":${json(policy)},"statements":[$ALLOW]}]""",
            bindings = """[${binding(subject = """{"role":"clerk"}""", app = "order-service", policy = policy)}]""",
        )
        policy == "cosec:root" -> platform(
            bindings = listOf(
                binding(subject = principal("cosec", "(platform)"), policy = policy).dropLast(1) +
                    ""","breakGlass":true,"expiresAt":"2999-12-31T00:00:00Z"}""",
            ),
        )
        else -> platform(
            policies = """[{"id":${json(policy)},"statements":[$ALLOW]}]""",
            bindings = listOf(binding(policy = policy)),
        )
    }

    private fun principal(id: String, tenant: String) = """{"principal":${json(id)},"tenant":${json(tenant)}}"""

    private fun binding(
        id: String = "g",
        subject: String = "\"*\"",
        app: String = "*",
        space: String = "*",
        policy: String = "p",
    ) = """{"id":${json(id)},"subject":$subject,"app":${json(app)},"space":${json(space)},"policy":${json(policy)}}"""

    private fun document(fields: String) = """{"schema":2,"revision":1,"publishedAt":"2026-10-26T00:00:00Z",$fields}"""

    private fun platform(
        policies: String = """[{"id":"p","statements":[$ALLOW]}]""",
        bindings: List<String> = emptyList(),
        header: String = "",
    ): Pair<SnapshotKey, String> {
        val written = bindings.joinToString(",", "[", "]")
        return SnapshotKey.Platform to document("""$header"policies":$policies,"bindings":$written""")
    }

    private fun app(policies: String = "[]", bindings: String = "[]", catalog: String = "") =
        SnapshotKey.App("order-service") to document("""$catalog"policies":$policies,"bindings":$bindings""")

    private fun ObjectType.items(name: String): StringType {
        val array = requireNotNull(field(name)).type as ArrayType
        return array.items as StringType
    }

    private fun ObjectType.string(name: String): StringType = requireNotNull(field(name)).type as StringType

    private fun json(value: Any): String = CoSecJson.mapper.writeValueAsString(value)

    private fun statement(action: String = "*", resource: String? = null, time: Map<String, Any>? = null): String =
        listOfNotNull(
            """"effect":"allow"""",
            """"action":${json(action)}""",
            resource?.let { """"resource":${json(it)}""" },
            time?.let { """"condition":{"time":${json(it)}}""" },
        ).joinToString(",", "{", "}")

    private fun compileError(placement: Placement, value: String): Exception? {
        placement.document?.let { document -> return document(value).let { (key, body) -> compiling(key, body) } }
        val roles = placement.roles ?: return compileError(placement.statement(value), placement.header(value))
        val body = """{"schema":2,"revision":1,"publishedAt":"2026-10-26T00:00:00Z","roles":${roles(value)}}"""
        return compiling(SnapshotKey.Tenant("acme"), body)
    }

    /** Null when the platform document holding [statement] (and [header] fields) compiles; otherwise why not. */
    private fun compileError(statement: String, header: String = ""): Exception? {
        val body = """{"schema":2,"revision":1,"publishedAt":"2026-10-26T00:00:00Z",$header""" +
            """"policies":[{"id":"p","statements":[$statement]}],""" +
            """"bindings":[{"id":"g","subject":"*","app":"*","space":"*","policy":"p"}]}"""
        return compiling(SnapshotKey.Platform, body)
    }

    private fun compiling(key: SnapshotKey, body: String): Exception? {
        val document = SnapshotDocuments.read(body.encodeToByteArray())
        return try {
            compiler.compile(key, document)
            null
        } catch (error: SnapshotFormatException) {
            error
        } catch (error: DocumentCompileException) {
            error
        }
    }

    private companion object {
        const val ALLOW = """{"effect":"allow","action":"*"}"""
        const val PERMISSION = "perm:"
    }

    @Test
    fun everyCounterExampleOfAnEightZeroStringIsPlaced() {
        val placed = placements.map { it.type }.toSet()
        val strings = eightZeroStrings().filter { it.counterExamples.isNotEmpty() }
        strings.filterNot { it in placed }.map { it.examples }.assert()
            .describedAs("8.0 strings with counter-examples but no placement here").isEmpty()
    }

    @Test
    fun examplesCompileAndCounterExamplesDoNot() {
        val failures = placements.flatMap { placement ->
            placement.type.examples.mapNotNull { example ->
                compileError(placement, example)?.let { "${placement.name}: example [$example] fails: $it" }
            } + placement.type.counterExamples.mapNotNull { invalid ->
                if (compileError(placement, invalid) == null) "${placement.name}: [$invalid] compiles" else null
            }
        }
        failures.assert().describedAs(failures.joinToString("\n")).isEmpty()
    }

    @Test
    fun aDayOutsideTheEnumDoesNotCompile() {
        compileError(statement(time = mapOf("daysOfWeek" to listOf("MON")))).assert().isNull()
        compileError(statement(time = mapOf("daysOfWeek" to listOf(FormatMutations.OUT_OF_ENUM)))).assert().isNotNull()
    }

    private fun eightZeroStrings(): List<StringType> {
        val found = ArrayList<StringType>()
        val seen = HashSet<FormatType>()
        fun visit(type: FormatType) {
            if (!seen.add(type)) return
            when (type) {
                is StringType -> found += type
                is ArrayType -> visit(type.items)
                is TupleType -> type.items.forEach(::visit)
                is MapType -> {
                    visit(type.keys)
                    visit(type.values)
                }
                is ObjectType -> type.fields.forEach { visit(it.type) }
                is AnyOf -> type.variants.forEach(::visit)
                is Ref -> Unit
                else -> Unit
            }
        }
        definitions.values.forEach(::visit)
        listOf(PolicyDocumentFormats.bindingSchema, PolicyDocumentFormats.catalogSchema).forEach { file ->
            file.root?.let(::visit)
            file.definitions.values.forEach(::visit)
        }
        return found
    }
}
