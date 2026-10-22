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

package me.ahoo.cosec.authorization

import me.ahoo.cosec.authorization.engine.PolicySnapshotCompiler
import me.ahoo.cosec.serialization.CoSecModule
import me.ahoo.cosec.snapshot.SnapshotDocument
import me.ahoo.cosec.snapshot.SnapshotKey
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import java.time.Instant
import tools.jackson.module.kotlin.jsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.util.concurrent.TimeUnit

/**
 * Compiling a policies document of [statements] statements (RFC 0003 §9): parsing, decoding through the matcher
 * factories, and adapting into rule groups. Only recorded, to read the real-data measurements of §4.8 against.
 *
 * Ten statements per policy, a tenth of the policies global, with path, method and condition matchers. The build's
 * `-PjmhMode` overrides the mode below; run it with `-PjmhMode=avgt`.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class SnapshotCompileBenchmark {
    @Param("10", "100", "1000", "10000")
    var statements: Int = 10

    // The benchmark jar keeps only one of the JacksonModule service files: the CoSec module is registered explicitly.
    private val compiler = PolicySnapshotCompiler(
        jsonMapper {
            addModule(kotlinModule())
            addModule(CoSecModule())
        }
    )
    private lateinit var document: SnapshotDocument

    @Setup
    fun setup() {
        val policies = (0 until (statements + STATEMENTS_PER_POLICY - 1) / STATEMENTS_PER_POLICY).joinToString(",") { policy ->
            val body = (0 until STATEMENTS_PER_POLICY).joinToString(",") { statement ->
                val action = when (statement % 3) {
                    0 -> """"/svc$policy/resource$statement/**""""
                    1 -> """{"path":{"pattern":"/svc$policy/{id}/op$statement","method":"GET"}}"""
                    else -> """["/svc$policy/a$statement/**","/svc$policy/b$statement/*"]"""
                }
                val condition = when (statement % 4) {
                    0 -> """{"authenticated":{}}"""
                    1 -> """{"in":{"part":"request.method","value":["GET","PUT"]}}"""
                    2 -> """{"inRole":{"value":"role$statement"}}"""
                    else -> """{"all":{}}"""
                }
                val effect = if (statement == 0) "deny" else "allow"
                """{"name":"s$statement","effect":"$effect","action":$action,"condition":$condition}"""
            }
            val type = if (policy % 10 == 0) "global" else "custom"
            """{"id":"policy$policy","name":"policy$policy","category":"","description":"","type":"$type","tenantId":"t","statements":[$body]}"""
        }
        val body = """{"schema":1,"revision":1,"publishedAt":"2026-10-21T08:00:00Z","policies":[$policies]}"""
        document = SnapshotDocument(SnapshotDocument.CURRENT_SCHEMA, 1, Instant.EPOCH, body.encodeToByteArray())
    }

    @Benchmark
    fun compile(): Any = compiler.policies.compile(SnapshotKey.Policies, document)

    private companion object {
        const val STATEMENTS_PER_POLICY = 10
    }
}
