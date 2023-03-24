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

import me.ahoo.cosec.api.configuration.asObject
import me.ahoo.cosec.configuration.JsonConfiguration
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class JsonConfigurationSerializerTest {

    val json = """
        {"type":"path","methods":["GET","POST"],"pattern":"#{principal.id}.*","part":{"kind":"request","name":"remoteIp"}}
    """.trimIndent()

    @Test
    fun serialize() {
        val jsonConfiguration = CoSecJsonSerializer.readValue(json, JsonConfiguration::class.java)
        assertThat(jsonConfiguration.has("type"), equalTo(true))
        assertThat(jsonConfiguration.has("path"), equalTo(false))
        assertThat(jsonConfiguration.getRequired("type").asString(), equalTo("path"))
        assertThat(jsonConfiguration.getRequired("methods").asStringList(), equalTo(listOf("GET", "POST")))
        assertThat(jsonConfiguration.getRequired("pattern").asString(), equalTo("#{principal.id}.*"))
        assertThat(
            jsonConfiguration.getRequired("part").asStringMap(),
            equalTo(mapOf("kind" to "request", "name" to "remoteIp")),
        )
        assertThat(
            jsonConfiguration.getRequired("part").asObject<Part>(),
            equalTo(Part("request", "remoteIp")),
        )
        val jsonString = CoSecJsonSerializer.writeValueAsString(jsonConfiguration)
        assertThat(jsonString, equalTo(json))
    }

    data class Part(val kind: String, val name: String)
}
