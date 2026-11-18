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

package me.ahoo.cosec.spring.boot.control

import me.ahoo.cocache.api.spi.CoherenceBackend
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.memory.InMemoryBackend
import me.ahoo.cocache.spring.boot.CoCacheAutoConfiguration
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.CoSecCacheSpecs
import me.ahoo.cosec.control.api.ControlHttp
import me.ahoo.cosec.runtime.shadow.CandidateSource
import me.ahoo.cosec.spring.boot.jwt.CoSecJwtAutoConfiguration
import me.ahoo.cosec.spring.boot.jwt.CoSecTokenRevocationCacheAutoConfiguration
import me.ahoo.cosec.spring.boot.jwt.JwtProperties
import me.ahoo.cosec.spring.boot.pipeline.CoSecPipelineAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.CoSecPipelineIntegrationAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.CoSecPipelineWebAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.CoSecSnapshotAutoConfiguration
import me.ahoo.cosec.spring.boot.pipeline.GATEWAY_GLOBAL_FILTER
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.assertj.AssertableReactiveWebApplicationContext
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.webflux.autoconfigure.HttpHandlerAutoConfiguration
import org.springframework.boot.webflux.autoconfigure.WebFluxAutoConfiguration
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * `cosec.control.enabled` (SNP-18): `false` leaves the whole control plane out of an instance whose classpath has it,
 * and that instance reads the documents and the candidates read-only, by the dotted key or the environment variable a
 * one-image fleet sets per role. A value that is neither `true` nor `false` fails startup.
 */
@ExtendWith(OutputCaptureExtension::class)
class ControlPlaneSwitchTest {
    private val runner = ReactiveWebApplicationContextRunner()
        .withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER))
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration::class.java,
                // Lazy: the templates are created, and nothing connects.
                DataRedisAutoConfiguration::class.java,
                DataRedisReactiveAutoConfiguration::class.java,
                CoCacheAutoConfiguration::class.java,
                HttpHandlerAutoConfiguration::class.java,
                WebFluxAutoConfiguration::class.java,
                CoSecTokenRevocationCacheAutoConfiguration::class.java,
                CoSecJwtAutoConfiguration::class.java,
                CoSecPipelineIntegrationAutoConfiguration::class.java,
                CoSecSnapshotAutoConfiguration::class.java,
                CoSecPipelineAutoConfiguration::class.java,
                CoSecPipelineWebAutoConfiguration::class.java,
                CoSecControlAutoConfiguration::class.java,
                CoSecControlRoutesAutoConfiguration::class.java,
            ),
        )
        .withPropertyValues(
            "cocache.backend=memory",
            "cosec.ip2region.enabled=false",
            "cosec.authorization.routes.location=",
            "${JwtProperties.PREFIX}.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
        )
        .withBean(CoherenceBackend::class.java, { InMemoryBackend() })

    /** `COSEC_CONTROL_ENABLED` as the operating system would give it: a system environment property source. */
    private val offByEnvironment = runner.withInitializer { context ->
        context.environment.propertySources.addFirst(
            SystemEnvironmentPropertySource("role", mapOf<String, Any>("COSEC_CONTROL_ENABLED" to "false"))
        )
    }

    /**
     * Every bean definition of the control plane: its class, or the class whose factory method declares it, is in
     * `me.ahoo.cosec.control` or the starter's control package. Over the definitions, so a bean added later outside
     * the condition is found, the control routes' router functions included.
     */
    private fun AssertableReactiveWebApplicationContext.controlBeans(): List<String> {
        val factory = beanFactory
        return factory.beanDefinitionNames.filter { name ->
            val definition = factory.getBeanDefinition(name)
            listOfNotNull(
                definition.beanClassName,
                (definition as? AnnotatedBeanDefinition)?.factoryMethodMetadata?.declaringClassName,
                factory.getType(name, false)?.name,
            ).any { type -> CONTROL_PACKAGES.any { type.startsWith(it) } }
        }
    }

    private fun AssertableReactiveWebApplicationContext.readOnly(): List<Boolean> {
        getBean(CandidateSource::class.java)
        val runtime = getBean(CacheRuntime::class.java)
        return listOf(CoSecCacheSpecs.POLICY_SNAPSHOT_CACHE_NAME, CoSecCacheSpecs.CANDIDATE_CACHE_NAME)
            .map { checkNotNull(runtime.admin(it)) { "$it was not created" }.readOnly }
    }

    @Test
    fun `switched on by default, the control plane and its routes are wired, and the stores written`() {
        runner.run { context ->
            context.startupFailure.assert().isNull()
            context.getBeansOfType(ControlHttp::class.java).assert().hasSize(1)
            // The guard sees the control plane's beans, its routes among them, when it is on.
            context.controlBeans().assert().contains("cosecControlStore", "cosecControlHttp", "cosecControlRoutes")
            context.readOnly().assert().containsOnly(false)
        }
    }

    @Test
    fun `cosec control enabled false wires nothing of the control plane, and the stores are read read-only`() {
        runner.withPropertyValues("${ControlPlaneCondition.ENABLED_KEY}=false").run { context ->
            context.startupFailure.assert().isNull()
            context.controlBeans().assert().isEmpty()
            context.readOnly().assert().containsOnly(true)
        }
    }

    @Test
    fun `the environment variable a one-image fleet sets per role does the same, through the condition and both stores`() {
        offByEnvironment.run { context ->
            context.startupFailure.assert().isNull()
            context.controlBeans().assert().isEmpty()
            context.readOnly().assert().containsOnly(true)
        }
    }

    @Test
    fun `a value that is neither true nor false fails startup, read neither as on nor as off`() {
        runner.withPropertyValues("${ControlPlaneCondition.ENABLED_KEY}=flase").run { context ->
            context.startupFailure.assert().isNotNull()
            generateSequence(context.startupFailure) { it.cause }.map { it.message.orEmpty() }
                .any { "${ControlPlaneCondition.ENABLED_KEY} must be true or false, not [flase]" in it }
                .assert().isTrue()
        }
    }

    @Test
    fun `an instance that switches the control plane off says so once at startup, one without it says nothing`(
        output: CapturedOutput,
    ) {
        runner.withPropertyValues("${ControlPlaneCondition.ENABLED_KEY}=false").run { context ->
            context.startupFailure.assert().isNull()
        }
        output.out.split(LINE).size.assert().isEqualTo(2)
        val before = output.out.length
        runner.withClassLoader(FilteredClassLoader(GATEWAY_GLOBAL_FILTER, "me.ahoo.cosec.control.api.ControlHttp"))
            .run { context -> context.startupFailure.assert().isNull() }
        output.out.substring(before).contains(LINE).assert().isFalse()
    }

    private companion object {
        val CONTROL_PACKAGES = listOf("me.ahoo.cosec.control.", "me.ahoo.cosec.spring.boot.control.")
        const val LINE = "Control plane off on this instance: cosec.control.enabled=false"
    }
}
