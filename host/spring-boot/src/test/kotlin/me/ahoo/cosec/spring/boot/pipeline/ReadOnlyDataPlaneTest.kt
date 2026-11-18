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

package me.ahoo.cosec.spring.boot.pipeline

import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.CoSecCacheSpecs
import me.ahoo.cosec.runtime.shadow.CandidateSource
import me.ahoo.cosec.spring.boot.control.ControlPlaneCondition
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * `SNP-18`: a process reads the snapshot documents and the candidates read-only exactly when it does not run the
 * control plane, decided by [ControlPlaneCondition], the condition the control plane is wired on; a
 * `cocache.caches.[name].read-only` property cannot change it either way.
 */
class ReadOnlyDataPlaneTest {
    private val runner = ApplicationContextRunner()
        .withPropertyValues("cocache.backend=memory", "cosec.jwt.enabled=false", "cosec.authorization.routes.location=")
        .withBean(CoherenceBackend::class.java, { InMemoryBackend() })
        .withUserConfiguration(
            JacksonAutoConfiguration::class.java,
            CoCacheAutoConfiguration::class.java,
            CoSecSnapshotAutoConfiguration::class.java,
            CoSecPipelineAutoConfiguration::class.java,
        )

    /** A data-plane process: `cosec-control` is not on its classpath. */
    private val dataPlane = runner.withClassLoader(FilteredClassLoader("me.ahoo.cosec.control.api.ControlHttp"))

    private fun ApplicationContextRunner.readOnly(): Map<String, Boolean> {
        var answer = emptyMap<String, Boolean>()
        run { context ->
            context.startupFailure.assert().isNull()
            context.getBean(CandidateSource::class.java)
            val runtime = context.getBean(CacheRuntime::class.java)
            answer = listOf(CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME, CoSecCacheSpecs.CANDIDATE_CACHE_NAME)
                .associateWith { checkNotNull(runtime.admin(it)) { "$it was not created" }.readOnly }
        }
        return answer
    }

    @Test
    fun `a data-plane process reads the documents and the candidates read-only`() {
        dataPlane.readOnly().values.assert().containsOnly(true)
    }

    @Test
    fun `a process that runs the control plane writes them`() {
        ControlPlaneCondition.present(javaClass.classLoader).assert().isTrue()
        runner.readOnly().values.assert().containsOnly(false)
    }

    private fun ApplicationContextRunner.refused(property: String, expected: String) {
        withPropertyValues(property).run { context ->
            assertThat(context).hasFailed()
            generateSequence(context.startupFailure) { it.cause }.map { it.message.orEmpty() }
                .any { "SNP-18" in it && expected in it }.assert().describedAs(property).isTrue()
        }
    }

    @Test
    fun `a property cannot make a data-plane process write the documents`() {
        dataPlane.refused("cocache.caches.PolicySnapshotCache.read-only=false", "read-write")
    }

    @Test
    fun `a property cannot make the control plane's process read the documents read-only`() {
        runner.refused("cocache.caches.PolicySnapshotCache.read-only=true", "read-only")
    }
}
