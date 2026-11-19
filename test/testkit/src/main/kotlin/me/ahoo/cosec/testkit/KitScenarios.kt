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

package me.ahoo.cosec.testkit

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.decision.DocumentKind
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * What makes a [PolicyTestKit] run fail rather than pass vacuously (UC-7): documents that never loaded, and scenarios
 * that lean on the example names the format would otherwise fill in (tenant `acme`, principal `alice`, action
 * `order:read`, resource `order/1`).
 */
internal object KitScenarios {
    private const val WITHOUT_DOCUMENT = "withoutDocument"

    /**
     * [scenario] names who asks and what: `credential` with `tenant` and `id`, or `"anonymous": true`, and `request`
     * with `action` and `resource`. Each that is missing fails, saying what the kit would otherwise have asked.
     */
    fun requireExplicit(scenario: JsonNode) {
        val credential = scenario.get("credential")
        val anonymous = credential?.get("anonymous")?.takeIf { it.isBoolean }?.asBoolean() == true
        val missing = buildList {
            if (credential == null) add("credential (tenant and id, or \"anonymous\": true)")
            if (credential != null && !anonymous) {
                if (!credential.has("tenant")) add("credential.tenant (the example's is acme)")
                if (!credential.has("id")) add("credential.id (the example's is alice)")
            }
            val request = scenario.get("request")
            if (request?.has("action") != true) add("request.action (the example's is order:read)")
            if (request?.has("resource") != true) add("request.resource (the example's is order/1)")
        }
        if (missing.isNotEmpty()) {
            throw ScenarioFormatException(
                "The scenario does not name ${missing.joinToString(", ")}: the kit would ask the format's example, " +
                    "which proves nothing of your documents. Name each",
            )
        }
    }

    /** [scenario] without its `withoutDocument`, and the documents it names: `tenant`, `app`. */
    fun withoutDocument(scenario: JsonNode): Pair<JsonNode, Set<DocumentKind>> {
        val node = scenario.get(WITHOUT_DOCUMENT) ?: return scenario to emptySet()
        val without = node.strings().mapTo(HashSet()) {
            when (it) {
                "tenant" -> DocumentKind.TENANT
                "app" -> DocumentKind.APP
                else -> throw ScenarioFormatException("withoutDocument names tenant or app, not [$it]")
            }
        }
        return (scenario.deepCopy() as ObjectNode).apply { remove(WITHOUT_DOCUMENT) } to without
    }

    /**
     * [directory] has a `platform.json`, and nothing the kit would not read: no other `.json` file or directory beside
     * it, `apps/`, `tenants/` and `report.json`, and no file in `apps/` or `tenants/` that is not a `.json`. Hidden
     * entries (a name beginning with `.`) are left alone.
     */
    fun requireReadable(directory: Path) {
        if (!directory.resolve(PolicyTestKit.PLATFORM_FILE).isRegularFile()) {
            throw ScenarioFormatException(
                "No ${PolicyTestKit.PLATFORM_FILE} in ${directory.toAbsolutePath()}: every request reads the platform " +
                    "document, and the kit would decide on an empty one. Put it there; to test without one, write an " +
                    "empty document there, {\"schema\":2,\"revision\":1,\"publishedAt\":\"2026-01-01T00:00:00Z\"}",
            )
        }
        val known =
            setOf(
                PolicyTestKit.PLATFORM_FILE,
                PolicyTestKit.APPS_DIRECTORY,
                PolicyTestKit.TENANTS_DIRECTORY,
                "report.json"
            )
        val unread = entries(directory).filter { it.name !in known && (it.isDirectory() || it.extension == "json") }
            .map { it.name } +
            listOf(PolicyTestKit.APPS_DIRECTORY, PolicyTestKit.TENANTS_DIRECTORY).flatMap { sub ->
                val path = directory.resolve(sub)
                if (path.isDirectory()) entries(path).filter { it.extension != "json" }.map { "$sub/${it.name}" } else emptyList()
            }
        if (unread.isNotEmpty()) {
            throw ScenarioFormatException(
                "The kit does not read ${unread.sorted()} in ${directory.toAbsolutePath()}: documents are " +
                    "${PolicyTestKit.PLATFORM_FILE}, ${PolicyTestKit.APPS_DIRECTORY}/<app>.json and " +
                    "${PolicyTestKit.TENANTS_DIRECTORY}/<tenant>.json. Move them there, or out of the directory",
            )
        }
    }

    private fun entries(directory: Path): List<Path> =
        Files.list(directory).use { paths -> paths.filter { !it.name.startsWith(".") }.toList() }
}
