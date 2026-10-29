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

@file:OptIn(ExperimentalCoSecApi::class)

package me.ahoo.cosec.testkit

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.model.condition.AttributeCatalog
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper

class DocumentSnapshotCompilerTest {
    private val keyed = ScenarioDocuments(
        JsonMapper.builder().build().readTree(
            """{"rateLimits":[{"id":"per-key","action":"*","permitsPerSecond":10,"key":"http.header.x-acme-key"}]}"""
        ),
        emptyMap(),
        emptyMap(),
    )

    /** RC1-21 (`CND-5`): a host's renamed API key header is refused as a credential header; elsewhere it is ordinary. */
    @Test
    fun `the credential headers it is given do not compile`() {
        DocumentSnapshotCompiler.with(AttributeCatalog.NONE).compile(keyed)
        val error = assertThrows<DocumentException> {
            DocumentSnapshotCompiler.with(AttributeCatalog.NONE, setOf("X-Acme-Key")).compile(keyed)
        }
        error.message.assert().contains("credential header")
    }
}
