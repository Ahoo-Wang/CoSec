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

package me.ahoo.cosec.testkit

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.engine.CompiledDocument
import tools.jackson.databind.JsonNode

/** The documents of a scenario, as written. */
@InternalCoSecApi
class ScenarioDocuments(val platform: JsonNode?, val apps: Map<String, JsonNode>, val tenants: Map<String, JsonNode>)

/** Every document of a scenario, compiled. */
@InternalCoSecApi
class CompiledSnapshot(
    val platform: CompiledDocument,
    val apps: Map<String, CompiledDocument>,
    val tenants: Map<String, CompiledDocument>,
)

/** Turns scenario documents into what the engine evaluates. */
@InternalCoSecApi
fun interface SnapshotCompiler {
    /** Throws [DocumentException] when a document does not compile. */
    fun compile(documents: ScenarioDocuments): CompiledSnapshot
}
