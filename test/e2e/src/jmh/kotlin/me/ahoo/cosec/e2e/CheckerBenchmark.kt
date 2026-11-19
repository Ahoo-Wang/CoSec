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

@file:OptIn(ExperimentalCoSecApi::class, InternalCoSecApi::class)

package me.ahoo.cosec.e2e

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.model.decision.Decision
import me.ahoo.cosec.runtime.AuthorizationChecker
import me.ahoo.cosec.runtime.snapshot.SnapshotOptions
import me.ahoo.cosec.spi.snapshot.SnapshotKey
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
 * `AuthorizationChecker.decide`: a check by host code, as the request it belongs to (RFC 0006 §7.6), through the same
 * end-to-end slice as [PipelineBenchmark], documents warm in the local cache. The context is that of one permitted
 * request of `user` with the `admin` role of app `app`. Scenarios: `permit`, a further action the role's grant
 * permits; `deny`, one a DENY statement refuses; `ended`, the permitted action asked after the request has ended
 * (`PIP-26`), refused before any document is read, the cost of the misuse path (its logging at DEBUG off).
 *
 * Each operation runs the check on the calling thread: a warm check never suspends, as a warm request does not.
 */
@State(Scope.Benchmark)
open class CheckerBenchmark {
    @Param("permit", "deny", "ended")
    lateinit var scenario: String

    private lateinit var slice: Slice
    private lateinit var checker: AuthorizationChecker
    private lateinit var context: SecurityContext
    private lateinit var resource: String

    @Setup(Level.Trial)
    fun setup() {
        slice = Slice(options = SnapshotOptions(), meterRegistry = SimpleMeterRegistry())
        slice.publish(SnapshotKey.Platform, PLATFORM)
        slice.publish(SnapshotKey.App("app"), APP)
        slice.publish(SnapshotKey.Tenant("acme"), "")
        val outcome = runBlocking { slice.evaluate(request("GET", "/orders/op1/detail", "user@acme:admin", app = "app")) }
        val security = checkNotNull(outcome.security) { "The request was not permitted: $outcome" }
        context = security.securityContext
        checker = AuthorizationChecker(slice.pipeline)
        resource = if (scenario == "deny") "/orders/internal/report" else "/orders/op9/detail"
        if (scenario == "ended") {
            security.end()
        }
        val expected = if (scenario == "permit") Decision.Permit::class else Decision.Deny::class
        check(expected.isInstance(check())) { "Scenario $scenario: ${check()}" }
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        slice.close()
    }

    @Benchmark
    fun check(): Decision {
        val result = CompletableFuture<Decision>()
        val call: suspend () -> Decision = { checker.decide(context, ACTION, resource) }
        call.startCoroutine(Continuation(EmptyCoroutineContext) { result.complete(it.getOrThrow()) })
        return result.join()
    }

    private companion object {
        const val ACTION = "http:GET"
        const val PLATFORM = ""","policies":[{"id":"public","statements":[{"effect":"allow",""" +
            """"action":"http:GET","resource":"http:path:/public/**"}]}],""" +
            """"bindings":[{"id":"g:public","subject":"*","app":"*","space":"*","policy":"public"}]"""
        val APP = ""","policies":[{"id":"orders","statements":[""" +
            (1..10).joinToString(",") { """{"effect":"allow","action":"http:*","resource":"http:path:/orders/op$it/**"}""" } +
            """,{"effect":"deny","action":"http:*","resource":"http:path:/orders/internal/**"}]}],""" +
            """"bindings":[{"id":"admin","subject":{"role":"admin"},"app":"app","space":"*","policy":"orders"}]"""
    }
}
