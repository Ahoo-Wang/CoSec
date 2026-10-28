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

package me.ahoo.cosec.migrate

/** Synthetic 7.x data as JSON, the way 7.x stores it. */
object Fixtures7x {
    fun statement(
        action: String = "\"*\"",
        effect: String = "allow",
        condition: String? = null,
        name: String = "s"
    ): String =
        """{"name":"$name","effect":"$effect","action":$action${condition?.let { ",\"condition\":$it" }.orEmpty()}}"""

    fun policy(
        id: String,
        type: String = "custom",
        statements: List<String> = listOf(statement()),
        condition: String? = null,
        tenantId: String = "(0)",
    ): String =
        """{"id":"$id","name":"$id","category":"","description":"","type":"$type","tenantId":"$tenantId"""" +
            (condition?.let { ",\"condition\":$it" }.orEmpty()) + ""","statements":[${statements.joinToString(",")}]}"""

    fun permission(id: String, action: String = "\"*\"", effect: String = "allow", condition: String? = null): String =
        """{"id":"$id","name":"$id","description":"","effect":"$effect","action":$action""" +
            (condition?.let { ",\"condition\":$it" }.orEmpty()) + "}"

    fun appPermission(id: String, vararg permissions: String, condition: String? = null, spaced: Boolean = false): String =
        """{"id":"$id"${condition?.let { ",\"condition\":$it" }.orEmpty()},""" +
            """"groups":[{"name":"g","description":"the group","spaced":$spaced,"permissions":[${permissions.joinToString(",")}]}]}"""

    /** A source as the converter reads it; ids and grant keys are as given. */
    fun source(
        policies: List<Pair<String, String>> = emptyList(),
        globalIndex: Set<String> = emptySet(),
        apps: List<Source7x.App> = emptyList(),
        marker: String? = "7",
    ): Source7x = Source7x(
        policies.map { (id, json) -> Source7x.Entry(id, "policy:$id", json.encodeToByteArray()) },
        globalIndex,
        apps,
        Source7x.Marker("meta:schema", marker),
        "global",
        "test",
    )

    fun app(appId: String, appPermission: String?, vararg grants: Pair<String, String>): Source7x.App = Source7x.App(
        appId,
        "app:$appId",
        appPermission?.let { Source7x.Entry(appId, "app:permission:$appId", it.encodeToByteArray()) },
        grants.map { (key, ids) ->
            val separator = key.indexOf('@')
            val (role, space) = if (separator < 0) key to "" else key.substring(0, separator) to key.substring(separator + 1)
            Source7x.Grant(role, space, "app:role:permission:$appId:$key", ids.encodeToByteArray())
        },
    )
}
