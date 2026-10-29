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

import io.swagger.v3.core.util.Json
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.PathItem
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.runtime.document.PolicyDocumentCodec
import me.ahoo.cosec.runtime.document.PolicyDocumentCompiler
import me.ahoo.cosec.runtime.route.RouteMapCodec
import me.ahoo.cosec.runtime.route.RouteResolution
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OpenAPIServiceGeneratorTest {
    private fun spec(paths: String, info: String = """{"title":"order","version":"1"}"""): OpenAPI =
        Json.mapper().readValue("""{"openapi":"3.0.1","info":$info,"paths":$paths}""", OpenAPI::class.java)

    private val api = spec(
        """
        {
          "/orders": {
            "get":  {"operationId":"list", "tags":["orders"], "summary":"List orders"},
            "post": {"operationId":"create", "tags":["orders"]}
          },
          "/orders/{orderId}": {
            "get":    {"operationId":"read", "tags":["orders"], "summary":"Read an order", "description":"One order"},
            "delete": {"operationId":"remove", "x-cosec-action":"order:delete"}
          },
          "/orders/{orderId}/items/{itemId}": {
            "put": {"operationId":"updateItem", "tags":["items"], "x-cosec-resource":"order/{orderId}/item/{itemId}"}
          },
          "/reports/monthly": {
            "get": {"operationId":"monthly", "tags":["reports"]}
          }
        }
        """.trimIndent(),
    )

    /** What the spec says one operation's action and resource are, read independently of the generator. */
    private fun expected(path: String, item: PathItem): List<Triple<String, String, Pair<String, String>>> =
        item.readOperationsMap().map { (method, operation) ->
            val action = operation.extensions?.get("x-cosec-action") as? String ?: "order:${operation.operationId}"
            val variables = Regex("""\{([^}]+)}""").findAll(path).map { it.groupValues[1] }.toList()
            val resource = operation.extensions?.get("x-cosec-resource") as? String
                ?: variables.lastOrNull()?.let { "order/{$it}" } ?: "order"
            val id = resource.substringAfter('/', "").let { template ->
                variables.fold(template) { text, variable -> text.replace("{$variable}", "v-$variable") }
            }
            Triple(method.name, path, action to id)
        }

    @Test
    fun `the generated route map maps every operation, and only operations, to its action and resource`() {
        val document = OpenAPIServiceGenerator.routeMap(api)
        document.service.assert().isEqualTo("order")
        val routes = RouteMapCodec.load(listOf("cosec-routes.json" to RouteMapCodec.encode(document)))
        val operations = api.paths.flatMap { (path, item) -> expected(path, item) }
        routes.size.assert().isEqualTo(operations.size)
        for ((method, path, actionAndId) in operations) {
            val concrete = Regex("""\{([^}]+)}""").replace(path) { "v-${it.groupValues[1]}" }
            val resolved = routes.resolve(method, concrete) as RouteResolution.Mapped
            "${resolved.action.namespace}:${resolved.action.verb}".assert().describedAs(
                "$method $path"
            ).isEqualTo(actionAndId.first)
            resolved.resource.type.assert().isEqualTo("order")
            resolved.resource.id.assert().describedAs("$method $path").isEqualTo(actionAndId.second)
            // Every method the operation does not declare stays in the http namespace.
            for (other in listOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS") - api.paths.getValue(path).readOperationsMap().keys.map {
                it.name
            }.toSet()) {
                routes.resolve(other, concrete).assert().describedAs("$other $path").isEqualTo(RouteResolution.Unmapped)
            }
        }
        routes.resolve("GET", "/elsewhere").assert().isEqualTo(RouteResolution.Unmapped)
    }

    @Test
    fun `the app document grants each action, with a catalog by tag, and compiles`() {
        val document = OpenAPIServiceGenerator.appDocument(api)
        document.kind.assert().isEqualTo(DocumentKind.APP)
        document.owner.assert().isEqualTo("order")
        PolicyDocumentCompiler().compile(
            PolicyDocumentCodec.decode(DocumentKind.APP, "order", PolicyDocumentCodec.encode(document))
        )
        val actions = document.policies.flatMap { policy -> policy.statements.flatMap { it.actions } }.toSet()
        actions.assert().isEqualTo(OpenAPIServiceGenerator.routeMap(api).routes.map { it.action }.toSet())
        document.policies.map { it.id.value }.assert().contains(
            "perm:order/read",
            "perm:order/delete",
            "perm:order/updateItem"
        )
        document.policies.forEach { it.statements.single().resources.assert().isEmpty() }
        val catalog = document.catalog!!.groups.associate { group -> group.name to group.permissions.map { it.id } }
        catalog.assert().isEqualTo(
            mapOf(
                "orders" to listOf("list", "create", "read"),
                "default" to listOf("delete"),
                "items" to listOf("updateItem"),
                "reports" to listOf("monthly")
            ),
        )
        document.catalog!!.groups.first().permissions[2].let {
            it.name.assert().isEqualTo("Read an order")
            it.description.assert().isEqualTo("One order")
        }
    }

    @Test
    fun `the service comes from info, or its x-cosec-service`() {
        OpenAPIServiceGenerator.service(
            spec("{}", """{"title":"t","version":"1","x-cosec-service":"billing"}""")
        ).assert().isEqualTo("billing")
        assertThrows<OpenAPIGenerationException> {
            OpenAPIServiceGenerator.service(
                spec("{}", """{"title":" ","version":"1"}""")
            )
        }
    }

    @Test
    fun `what cannot become unambiguous routes and actions fails generation`() {
        fun fails(paths: String, message: String) {
            assertThrows<OpenAPIGenerationException> {
                OpenAPIServiceGenerator.routeMap(spec(paths))
                OpenAPIServiceGenerator.appDocument(spec(paths))
            }.message.assert().contains(message)
        }
        fails("""{"/a":{"get":{"operationId":"x"}},"/b":{"get":{"operationId":"x"}}}""", "used twice")
        fails("""{"/a":{"get":{}}}""", "neither an operationId")
        fails(
            """{"/orders/{id}":{"get":{"operationId":"read"}},"/orders/export":{"get":{"operationId":"export"}}}""",
            "may match the same request"
        )
        fails("""{"/a":{"get":{"operationId":"x","x-cosec-action":"billing:pay"}}}""", "not of service [order]")
        fails("""{"/a":{"get":{"operationId":"has space"}}}""", "action")
    }
}
