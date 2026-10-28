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

package me.ahoo.cosec.migrate

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.json.CoSecJson
import tools.jackson.core.JacksonException
import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads 7.x policy data exported as files (RFC 0004 §8.2 `--from-files`), the format of RFC 0002 §8.2.1:
 *
 * - `policies.json`: an array of 7.x policies, each with its `id`;
 * - `global-policies.json`: an array of the ids in the global policy index;
 * - `app-role-permissions.json`: an array with one entry per app,
 *   `{ "appPermission": { "id": "billing", ... }, "grants": { "admin": ["*"], "auditor@s1": ["invoice.read"] } }`, or
 *   `{ "appId": "reports", "grants": { ... } }` for an app without an app permission.
 *
 * An entry that is not what its file needs fails the read with [IllegalArgumentException]: the export is malformed,
 * not the data in it.
 */
object ExportDirectory {
    const val POLICIES_FILE = "policies.json"
    const val GLOBAL_POLICIES_FILE = "global-policies.json"
    const val APP_ROLE_PERMISSIONS_FILE = "app-role-permissions.json"

    /** Optional: the deployment's rate limit rules, as `LocalRateLimitRuleLoader` reads them. */
    const val RATE_LIMIT_RULES_FILE = "rate-limit-rules.json"

    private val reader = CoSecJson.mapper.reader().with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
    private val writer = CoSecJson.mapper.writer()

    fun read(directory: Path): Source7x {
        val input = HashMap<String, ByteArray>()
        val policies = array(directory, POLICIES_FILE, input).mapIndexed { i, policy ->
            val id = policy.get("id")?.takeIf { it.isString }?.asString()
            require(id != null) { "$POLICIES_FILE[$i] has no string id." }
            Source7x.Entry(id, "$POLICIES_FILE[$i]", writer.writeValueAsBytes(policy))
        }
        val globalIndex = array(directory, GLOBAL_POLICIES_FILE, input).mapIndexed { i, id ->
            require(id.isString) { "$GLOBAL_POLICIES_FILE[$i] is not a policy id." }
            id.asString()
        }.toSet()
        val apps = array(
            directory,
            APP_ROLE_PERMISSIONS_FILE,
            input
        ).mapIndexed { i, entry -> app(entry, "$APP_ROLE_PERMISSIONS_FILE[$i]") }
        val rules = directory.resolve(
            RATE_LIMIT_RULES_FILE
        ).takeIf { Files.isRegularFile(it) }?.let { rateLimitRules(it, input) }.orEmpty()
        return Source7x(policies, globalIndex, apps, null, GLOBAL_POLICIES_FILE, Source7x.sha256(input), rules)
    }

    /** The rules in [file]: one rule object or an array of them, each with its `id`. */
    fun rateLimitRules(file: Path, input: MutableMap<String, ByteArray> = HashMap()): List<Source7x.Entry> {
        val bytes = Files.readAllBytes(file)
        input[file.fileName.toString()] = bytes
        val node = try {
            reader.readTree(bytes)
        } catch (error: JacksonException) {
            throw IllegalArgumentException("$file is not valid JSON: ${error.originalMessage}", error)
        }
        val rules = if (node.isArray) node.values().toList() else listOf(node)
        return rules.mapIndexed { i, rule ->
            val id = rule.get("id")?.takeIf { it.isString }?.asString()
            require(id != null) { "${file.fileName}[$i] has no string id." }
            Source7x.Entry(id, "${file.fileName}[$i]", writer.writeValueAsBytes(rule))
        }
    }

    private fun app(entry: JsonNode, location: String): Source7x.App {
        val appPermission = entry.get("appPermission")?.takeUnless { it.isNull }
        val appId = appPermission?.get("id")?.takeIf { it.isString }?.asString()
            ?: entry.get("appId")?.takeIf { it.isString }?.asString()
        require(appId != null) { "$location has neither an appPermission with an id nor an appId." }
        val grants = entry.get("grants")?.properties().orEmpty().map { (key, permissionIds) ->
            val separator = key.indexOf('@')
            val (roleId, spaceId) = if (separator < 0) key to "" else key.substring(0, separator) to key.substring(separator + 1)
            Source7x.Grant(roleId, spaceId, "$location.grants.$key", writer.writeValueAsBytes(permissionIds))
        }
        val permission = appPermission?.let {
            Source7x.Entry(appId, "$location.appPermission", writer.writeValueAsBytes(it))
        }
        return Source7x.App(appId, location, permission, grants)
    }

    private fun array(directory: Path, file: String, input: MutableMap<String, ByteArray>): List<JsonNode> {
        val path = directory.resolve(file)
        require(Files.isRegularFile(path)) { "$path is missing." }
        val bytes = Files.readAllBytes(path)
        input[file] = bytes
        val node = try {
            reader.readTree(bytes)
        } catch (error: JacksonException) {
            throw IllegalArgumentException("$file is not valid JSON: ${error.originalMessage}", error)
        }
        require(node != null && node.isArray) { "$file is not a JSON array." }
        return node.values().toList()
    }
}
