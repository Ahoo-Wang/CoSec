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

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.runtime.Outcome
import me.ahoo.cosec.runtime.http.HttpFacts
import me.ahoo.cosec.snapshot.SnapshotKey
import me.ahoo.cosec.snapshot.SnapshotOptions
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * `Pipeline.evaluate` through the end-to-end slice (RFC 0008 §3.8 "Pipeline throughput", §4.4.3): documents in real
 * Redis, read through CoCache, installed and warm, so each operation is what a request costs once the snapshot is in
 * the local cache.
 *
 * The data is 7.0's `SimpleAuthorizationBenchmark` (tag `v7.0.1`) converted as RFC 0004 §5.1 converts it: three global
 * policies with `Anyone` bindings, two attachable principal policies the token attaches, and app `app`'s permissions as
 * permission policies granted to `viewer` (twenty) and `admin` (`*`). Scenarios: `global`, a global ALLOW permits;
 * `role`, the `*` grant permits; `none`, nothing does (403); `anonymous`, no token, a global ALLOW permits. `blocked`
 * joins with the blacklist stage (RFC 0006 PR-4) and `rate-limited` with PR-5.
 *
 * Each operation starts the request as an adapter would and finishes it on the calling thread: a warm request never
 * suspends (`SliceBlockHoundTest`); one that did would be waited for, and counted.
 */
@State(Scope.Benchmark)
open class PipelineBenchmark {
    @Param("global", "role", "none", "anonymous")
    lateinit var scenario: String

    private lateinit var slice: Slice
    private lateinit var facts: HttpFacts

    @Setup(Level.Trial)
    fun setup() {
        // The default revalidation interval, as in production; the tests' 200 ms would add background passes.
        slice = Slice(options = SnapshotOptions())
        slice.publish(SnapshotKey.Platform, platform())
        slice.publish(SnapshotKey.App("app"), app())
        slice.publish(SnapshotKey.Tenant("acme"), "")
        val token = "user@acme:viewer,admin|principal-a,principal-b".takeUnless { scenario == "anonymous" }
        val path = when (scenario) {
            "global", "anonymous" -> "/public/resource3/page"
            "role" -> "/orders/op49/detail"
            "none" -> "/unknown/path"
            else -> error("Unknown scenario $scenario")
        }
        facts = request("GET", path, token, app = "app")
        val outcome = runBlocking { slice.evaluate(facts) }
        check(outcome.proceed == (scenario != "none")) { "Scenario $scenario: $outcome" }
        check(evaluate().status == outcome.status)
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        slice.close()
    }

    @Benchmark
    fun evaluate(): Outcome {
        val result = CompletableFuture<Outcome>()
        val call: suspend () -> Outcome = { slice.evaluate(facts) }
        call.startCoroutine(Continuation(EmptyCoroutineContext) { result.complete(it.getOrThrow()) })
        return result.join()
    }

    private fun statement(name: String, effect: String, pattern: String) =
        """{"name":"$name","effect":"$effect","action":"http:*","resource":"http:path:$pattern"}"""

    /** A 7.x policy: [count] ALLOWs on `/<prefix>/resource<n>` and below, then a DENY on `/<prefix>/admin` and below. */
    private fun policy(id: String, prefix: String, count: Int, attachable: Boolean = false): String {
        val statements = (1..count).map { statement("s$it", "allow", "/$prefix/resource$it/**") } +
            statement("deny", "deny", "/$prefix/admin/**")
        return """{"id":"$id","attachable":$attachable,"statements":[${statements.joinToString(",")}]}"""
    }

    private fun platform(): String {
        val global = listOf(policy("global-public", "public", 10), policy("global-docs", "docs", 10), policy("global-health", "health", 5))
        val principal = listOf(policy("principal-a", "reports", 10, true), policy("principal-b", "exports", 10, true))
        val bindings = listOf("global-public", "global-docs", "global-health")
            .map { """{"id":"g:$it","subject":"*","app":"*","space":"*","policy":"$it"}""" }
        return ""","policies":[${(global + principal).joinToString(",")}],"bindings":[${bindings.joinToString(",")}]"""
    }

    private fun app(): String {
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

    private companion object {
        const val PERMISSIONS = 50
        const val VIEWER_GRANTS = 20
    }
}
