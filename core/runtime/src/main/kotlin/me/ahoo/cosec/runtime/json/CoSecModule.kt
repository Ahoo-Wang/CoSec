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

package me.ahoo.cosec.runtime.json

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.model.scope.Scope
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.deser.std.StdDeserializer
import tools.jackson.databind.module.SimpleModule

/**
 * Jackson module for CoSec types, found by [CoSecJson] through `META-INF/services`: [PolicyType] as audit
 * events write it (lower case), and the refusal to read a resolved [Scope].
 *
 * @see CoSecJson
 */
@InternalCoSecApi
class CoSecModule : SimpleModule() {
    init {
        addSerializer(PolicyType::class.java, JsonPolicyTypeSerializer)
        addDeserializer(PolicyType::class.java, JsonPolicyTypeDeserializer)
        // Only ScopeResolver builds a resolved scope (SCP-5); Jackson would otherwise call its constructor by reflection.
        addDeserializer(Scope::class.java, RefusingScopeDeserializer)
    }
}

/** A resolved scope is never read from JSON, whatever the input: a client could otherwise name its own roles. */
private object RefusingScopeDeserializer : StdDeserializer<Scope>(Scope::class.java) {
    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Scope =
        throw IllegalStateException("A resolved Scope is never deserialized; only ScopeResolver builds one.")
}
