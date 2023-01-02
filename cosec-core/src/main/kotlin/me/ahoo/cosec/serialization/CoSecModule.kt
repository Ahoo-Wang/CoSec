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

package me.ahoo.cosec.serialization

import com.fasterxml.jackson.databind.module.SimpleModule
import me.ahoo.cosec.api.policy.ActionMatcher
import me.ahoo.cosec.api.policy.ConditionMatcher
import me.ahoo.cosec.api.policy.Effect
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.api.policy.PolicyType
import me.ahoo.cosec.api.policy.Statement
import me.ahoo.cosec.configuration.JsonConfiguration

class CoSecModule : SimpleModule() {
    init {
        addSerializer(ActionMatcher::class.java, JsonActionMatcherSerializer)
        addDeserializer(ActionMatcher::class.java, JsonActionMatcherDeserializer)
        addSerializer(ConditionMatcher::class.java, JsonConditionMatcherSerializer)
        addDeserializer(ConditionMatcher::class.java, JsonConditionMatcherDeserializer)
        addSerializer(Effect::class.java, JsonEffectSerializer)
        addDeserializer(Effect::class.java, JsonEffectDeserializer)
        addSerializer(Statement::class.java, JsonStatementSerializer)
        addDeserializer(Statement::class.java, JsonStatementDeserializer)
        addSerializer(PolicyType::class.java, JsonPolicyTypeSerializer)
        addDeserializer(PolicyType::class.java, JsonPolicyTypeDeserializer)
        addSerializer(Policy::class.java, JsonPolicySerializer)
        addDeserializer(Policy::class.java, JsonPolicyDeserializer)
        addSerializer(JsonConfiguration::class.java, JsonConfigurationSerializer)
        addDeserializer(JsonConfiguration::class.java, JsonConfigurationDeserializer)
    }
}
