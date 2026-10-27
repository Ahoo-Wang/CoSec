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

package me.ahoo.cosec.openapi.generator

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import me.ahoo.cosec.engine.flat.StatementSource
import me.ahoo.cosec.model.Effect
import me.ahoo.cosec.model.PolicyId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.route.Route
import me.ahoo.cosec.route.RouteMap
import me.ahoo.cosec.route.RouteMapCodec
import me.ahoo.cosec.route.RouteMapDocument
import me.ahoo.cosec.route.RouteMapException
import me.ahoo.cosec.snapshot.document.Catalog
import me.ahoo.cosec.snapshot.document.CatalogGroup
import me.ahoo.cosec.snapshot.document.CatalogPermission
import me.ahoo.cosec.snapshot.document.DocumentCompileException
import me.ahoo.cosec.snapshot.document.PolicyDefinition
import me.ahoo.cosec.snapshot.document.PolicyDocument
import me.ahoo.cosec.snapshot.document.PolicyDocumentCompiler
import java.time.Instant

/** An OpenAPI document the generator cannot turn into routes and actions; it names the operation. */
class OpenAPIGenerationException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * Generates a service's route map and its app document from its OpenAPI document (RFC 0005 §5.3), so that policies
 * name `service:verb` actions instead of paths:
 *
 * - `service` is `info.title`, or `info.x-cosec-service`;
 * - each operation is one route: its `action` is `<service>:<operationId>`, or the operation's `x-cosec-action`; its
 *   `resource` is `<service>/{<last path variable>}` (`<service>` without one), or `x-cosec-resource`;
 * - the app document (app = service) holds a catalog, one group per tag, and a permission policy
 *   `perm:<service>/<verb>` per action, allowing it on any resource; role bindings are the operator's.
 *
 * Generation fails ([OpenAPIGenerationException]) on an operation without an action, an `operationId` used twice,
 * or a route map that would not load (two operations one request could match; see [RouteMap]).
 */
object OpenAPIServiceGenerator {
    const val SERVICE_EXTENSION = "x-cosec-service"
    const val ACTION_EXTENSION = "x-cosec-action"
    const val RESOURCE_EXTENSION = "x-cosec-resource"
    const val DEFAULT_GROUP = "default"
    private val VARIABLE = Regex("""\{([^{}]+)}""")

    /** One operation, as generated. */
    class GeneratedOperation(val route: Route, val operation: Operation)

    fun service(openAPI: OpenAPI): String =
        (openAPI.info?.extensions?.get(SERVICE_EXTENSION) as? String ?: openAPI.info?.title)
            ?.takeIf { it.isNotBlank() }
            ?: throw OpenAPIGenerationException("The OpenAPI document names no service: set info.title or info.$SERVICE_EXTENSION")

    fun operations(openAPI: OpenAPI): List<GeneratedOperation> {
        val service = service(openAPI)
        val operationIds = HashSet<String>()
        return openAPI.paths.orEmpty().flatMap { (path, item) ->
            item.readOperationsMap().map { (method, operation) ->
                val where = "${method.name} $path"
                operation.operationId?.let { id ->
                    if (!operationIds.add(
                            id
                        )
                    ) {
                        throw OpenAPIGenerationException("$where: operationId [$id] is used twice")
                    }
                }
                val action = extension(operation, ACTION_EXTENSION)
                    ?: operation.operationId?.let { "$service:$it" }
                    ?: throw OpenAPIGenerationException("$where: the operation has neither an operationId nor $ACTION_EXTENSION")
                val resource = extension(operation, RESOURCE_EXTENSION)
                    ?: VARIABLE.findAll(path).lastOrNull()?.let { "$service/{${it.groupValues[1]}}" }
                    ?: service
                GeneratedOperation(Route(method.name, path, action, resource), operation)
            }
        }
    }

    /** The route map; checked as a host loads it, so a map that would not load is never generated. */
    fun routeMap(openAPI: OpenAPI): RouteMapDocument {
        val document = RouteMapDocument(service(openAPI), operations(openAPI).map { it.route })
        try {
            RouteMapCodec.load(listOf("generated" to RouteMapCodec.encode(document)))
        } catch (error: RouteMapException) {
            throw OpenAPIGenerationException(error.message.orEmpty(), error)
        }
        return document
    }

    /** The app document of the service: its catalog and a permission policy per action, compiled before it is returned. */
    fun appDocument(openAPI: OpenAPI, revision: Long = 1, publishedAt: Instant = Instant.now()): PolicyDocument {
        val service = service(openAPI)
        val operations = operations(openAPI)
        val permissions = LinkedHashMap<String, Pair<String, Operation>>()
        for (generated in operations) {
            val (actionService, verb) = generated.route.action.split(
                ':',
                limit = 2
            ).let { it[0] to it.getOrElse(1) { "" } }
            if (actionService != service) {
                throw OpenAPIGenerationException(
                    "${generated.route.method} ${generated.route.path}: action [${generated.route.action}] is not of " +
                        "service [$service]; its permission belongs to that service's app document",
                )
            }
            permissions.putIfAbsent(verb, generated.route.action to generated.operation)
        }
        val policies = permissions.map { (verb, value) ->
            PolicyDefinition(
                PolicyId(PolicyDocumentCompiler.permissionPrefix(service) + verb),
                attachable = false,
                condition = null,
                statements = listOf(StatementSource(verb, Effect.ALLOW, listOf(value.first))),
            )
        }
        val groups = LinkedHashMap<String, MutableList<CatalogPermission>>()
        for ((verb, value) in permissions) {
            val operation = value.second
            val permission =
                CatalogPermission(verb, operation.summary?.ifBlank { null }, operation.description?.ifBlank { null })
            groups.getOrPut(operation.tags?.firstOrNull() ?: DEFAULT_GROUP) { ArrayList() } += permission
        }
        val document = PolicyDocument(
            DocumentKind.APP,
            service,
            revision,
            publishedAt,
            policies,
            emptyList(),
            Catalog(groups.map { (name, entries) -> CatalogGroup(name, permissions = entries) }),
        )
        try {
            PolicyDocumentCompiler().compile(document)
        } catch (error: DocumentCompileException) {
            throw OpenAPIGenerationException("The app document of [$service] does not compile: ${error.message}", error)
        }
        return document
    }

    private fun extension(operation: Operation, name: String): String? =
        (operation.extensions?.get(name) as? String)?.takeIf { it.isNotBlank() }
}
