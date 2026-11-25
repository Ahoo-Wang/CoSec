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

package me.ahoo.cosec.compat.side

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.document.PolicyDocumentCompiler
import me.ahoo.cosec.document.SnapshotDocuments
import me.ahoo.cosec.engine.pattern.ActionPattern
import me.ahoo.cosec.engine.pattern.ResourcePattern
import me.ahoo.cosec.engine.pattern.ResourceTarget
import me.ahoo.cosec.model.Action
import me.ahoo.cosec.model.Resource
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * A policy document (MIX-11): `document.compile` reads and compiles a document of `key` (`platform`, `app:<id>`,
 * `tenant:<id>`) as the snapshot source does, `ok:<kind>` when this version would install it. `pattern.matches` parses
 * `pattern`, an action's or a resource's (`kind`), as a statement's is, and answers for each of `values` (one a line: an
 * action `service:verb`, a resource `http:path:<path>` or `<type>/<id>`) whether it matches, `ok:` and `1` or `0` each.
 */
internal object DocumentOperations {
    val ALL: Map<String, (Map<String, String>) -> String> = mapOf(
        "document.compile" to { arguments ->
            val key = key(arguments.getValue("key"))
            val compiled = PolicyDocumentCompiler()
                .compile(key, SnapshotDocuments.read(arguments.getValue("body").encodeToByteArray()))
            "ok:${compiled.kind}"
        },
        "pattern.matches" to { arguments ->
            val pattern = arguments.getValue("pattern")
            val values = arguments.getValue("values").lines()
            val matches: (String) -> Boolean = when (arguments.getValue("kind")) {
                "action" -> ActionPattern.parse(pattern).let { parsed ->
                    { value -> parsed.matches(Action(value.substringBefore(':'), value.substringAfter(':'))) }
                }
                "resource" -> ResourcePattern.parse(pattern).let { parsed ->
                    { value -> parsed.matches(ResourceTarget(resource(value))) }
                }
                else -> throw IllegalArgumentException("no pattern kind ${arguments["kind"]}")
            }
            "ok:" + values.joinToString("") { if (matches(it)) "1" else "0" }
        },
    )

    private fun resource(text: String): Resource = if (text.startsWith(ResourcePattern.HTTP_PREFIX)) {
        Resource(ResourcePattern.HTTP_TYPE, text.removePrefix(ResourcePattern.HTTP_PREFIX))
    } else {
        Resource(text.substringBefore('/'), text.substringAfter('/'))
    }

    fun key(text: String): SnapshotKey = when {
        text == "platform" -> SnapshotKey.Platform
        text.startsWith("app:") -> SnapshotKey.App(text.removePrefix("app:"))
        text.startsWith("tenant:") -> SnapshotKey.Tenant(text.removePrefix("tenant:"))
        else -> throw IllegalArgumentException("no document key $text")
    }
}
