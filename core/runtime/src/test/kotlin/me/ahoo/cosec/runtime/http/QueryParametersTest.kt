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

package me.ahoo.cosec.runtime.http

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.runtime.PipelineFixtures
import me.ahoo.cosec.runtime.PipelineOptions
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/** RC1-5: what a gateway removes from a query so that no credential the pipeline could read goes downstream. */
class QueryParametersTest {
    @Test
    fun `the authorization parameter goes, however its name is written`() {
        QueryParameters.without("authorization=t&a=1", "authorization").assert().isEqualTo("a=1")
        QueryParameters.without("a=1&%61uthorization=t&b=2", "authorization").assert().isEqualTo("a=1&b=2")
        QueryParameters.without("Authorization=t", "authorization").assert().isNull()
        QueryParameters.without("authorization", "authorization").assert().isNull()
        QueryParameters.without("authorization%zz=t&a=1", "authorization").assert().isEqualTo("authorization%zz=t&a=1")
    }

    @Test
    fun `a query without it is the same query`() {
        val query = "a=1&b=%20"
        QueryParameters.without(query, "authorization").assert().isSameAs(query)
        QueryParameters.without("authorizations=1", "authorization").assert().isEqualTo("authorizations=1")
        QueryParameters.without(null, "authorization").assert().isNull()
        QueryParameters.without("", "authorization").assert().isEmpty()
    }

    @Test
    fun `the pipeline names the headers and the query parameter it reads credentials from`() {
        val pipeline = PipelineFixtures.pipeline {
            it.copy(options = PipelineOptions(apiKeyHeader = "X-Service-Key"))
        }
        pipeline.credentialHeaders.assert().containsExactlyInAnyOrder("authorization", "X-Service-Key")
        pipeline.withoutQueryToken("authorization=t&page=2").assert().isEqualTo("page=2")
    }
}
