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

package me.ahoo.cosec.runtime.http

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.time.Duration

/** `PIP-34`: the body of a refusal is the host's renderer's, or CoSec's own, and a failing renderer never changes the refusal. */
class ProblemBodiesTest {
    private val problem = Problem(ProblemType.TOKEN_EXPIRED, "req-1", "trace-1")

    @Test
    fun `without a renderer the body is CoSec's own, byte for byte`() {
        for (type in ProblemType.entries) {
            val each = Problem(type, "r", null)
            ProblemBodies.DEFAULT.body(each).assert().isEqualTo(ProblemEncoder.encode(each))
            ProblemBodies(ProblemRenderer.DEFAULT).body(each).assert().isEqualTo(ProblemEncoder.encode(each))
        }
        ProblemRenderer.DEFAULT.render(problem).assert().isEqualTo(ProblemEncoder.encode(problem))
        ProblemBodies.DEFAULT.failures().assert().isZero()
    }

    @Test
    fun `a renderer's bytes are the body, and it is given the problem only`() {
        var seen: Problem? = null
        val bodies = ProblemBodies(
            ProblemRenderer { given ->
                seen = given
                """{"type":"urn:host:error:${given.type.code}","status":${given.status},"cosec":"${given.type.slug}"}"""
                    .toByteArray()
            }
        )
        String(bodies.body(problem)).assert()
            .isEqualTo("""{"type":"urn:host:error:TOKEN_EXPIRED","status":401,"cosec":"token-expired"}""")
        seen.assert().isSameAs(problem)
        bodies.failures().assert().isZero()
    }

    @Test
    fun `a renderer that throws, answers no bytes or answers null is replaced by CoSec's body, and counted`() {
        val nullAnswer = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(ProblemRenderer::class.java)
        ) { _, _, _ ->
            null
        } as ProblemRenderer
        val failing = listOf(
            ProblemRenderer { error("broken") },
            ProblemRenderer { throw IllegalArgumentException("broken") },
            ProblemRenderer { ByteArray(0) },
            nullAnswer,
        )
        for (renderer in failing) {
            val bodies = ProblemBodies(renderer)
            bodies.body(problem).assert().describedAs("$renderer").isEqualTo(ProblemEncoder.encode(problem))
            bodies.body(problem).assert().isEqualTo(ProblemEncoder.encode(problem))
            bodies.failures().assert().describedAs("$renderer").isEqualTo(2)
        }
    }

    @Test
    fun `an error that is not an exception is not swallowed`() {
        val bodies = ProblemBodies(ProblemRenderer { throw OutOfMemoryError("out") })
        runCatching { bodies.body(problem) }.exceptionOrNull().assert().isInstanceOf(OutOfMemoryError::class.java)
    }

    @Test
    fun `failures are logged at most once per interval, and counted every time`() {
        var now = 0L
        val bodies = ProblemBodies(ProblemRenderer { error("broken") }, Duration.ofSeconds(60)) { now }
        repeat(3) { bodies.body(problem) }
        now += Duration.ofSeconds(61).toNanos()
        bodies.body(problem)
        bodies.failures().assert().isEqualTo(4)
    }
}
