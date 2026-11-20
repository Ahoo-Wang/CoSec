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

package me.ahoo.cosec.e2e

/**
 * The documents of [PipelineBenchmark] and [EdgeRequestBenchmark]: 7.0's `SimpleAuthorizationBenchmark` (tag `v7.0.1`)
 * as RFC 0004 §5.1 converts it, so the two benchmarks price the same decision.
 */
internal object SliceDocuments {
    const val PERMISSIONS = 50
    const val VIEWER_GRANTS = 20

    private fun statement(name: String, effect: String, pattern: String) =
        """{"name":"$name","effect":"$effect","action":"http:*","resource":"http:path:$pattern"}"""

    /** A 7.x policy: [count] ALLOWs on `/<prefix>/resource<n>` and below, then a DENY on `/<prefix>/admin` and below. */
    private fun policy(id: String, prefix: String, count: Int, attachable: Boolean = false): String {
        val statements = (1..count).map { statement("s$it", "allow", "/$prefix/resource$it/**") } +
            statement("deny", "deny", "/$prefix/admin/**")
        return """{"id":"$id","attachable":$attachable,"statements":[${statements.joinToString(",")}]}"""
    }

    fun platform(): String {
        val global = listOf(policy("global-public", "public", 10), policy("global-docs", "docs", 10), policy("global-health", "health", 5))
        val principal = listOf(policy("principal-a", "reports", 10, true), policy("principal-b", "exports", 10, true))
        val bindings = listOf("global-public", "global-docs", "global-health")
            .map { """{"id":"g:$it","subject":"*","app":"*","space":"*","policy":"$it"}""" }
        return ""","policies":[${(global + principal).joinToString(",")}],"bindings":[${bindings.joinToString(",")}]"""
    }

    fun app(): String {
        val permissions = (1..PERMISSIONS).map { statement("p$it", "allow", "/orders/op$it/**") } +
            statement("deny-internal", "deny", "/orders/internal/**")
        val viewer = (1..VIEWER_GRANTS).map { "p$it" }
        val policies = viewer.map { id ->
            """{"id":"perm:app/$id","statements":[${permissions[id.removePrefix("p").toInt() - 1]}]}"""
        } + """{"id":"perm:app/*","statements":[${permissions.joinToString(",")}]}"""
        val bindings = viewer.map { """{"id":"viewer/$it","subject":{"role":"viewer"},"app":"app","space":"*","policy":"perm:app/$it"}""" } +
            """{"id":"admin/*","subject":{"role":"admin"},"app":"app","space":"*","policy":"perm:app/*"}"""
        return ""","policies":[${policies.joinToString(",")}],"bindings":[${bindings.joinToString(",")}]"""
    }
}
