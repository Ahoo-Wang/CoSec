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

package me.ahoo.cosec.spring.boot

import io.mockk.mockk
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.annotation.AnnotatedElementUtils
import java.lang.reflect.Method

/**
 * `STA-3`: the starter's checks are [StartupTask]s, which lazy initialization never defers. A `@Bean` method that returns
 * a bare [InitializingBean] or [SmartInitializingSingleton] (or hides its type as `Any`) would be lazy again under
 * `spring.main.lazy-initialization=true`, and its check would never run: no auto-configuration of the starter has one.
 */
class StartupTaskTest {
    /** Every auto-configuration the starter declares. */
    private val autoConfigurations: List<Class<*>> by lazy {
        val imports = javaClass.classLoader.getResources(
            "META-INF/spring/${AutoConfiguration::class.java.name}.imports"
        )
            .toList().flatMap { url -> url.openStream().bufferedReader().readLines() }
            .map { it.substringBefore('#').trim() }
            .filter { it.startsWith("me.ahoo.cosec.") }
        imports.assert().isNotEmpty()
        imports.map { Class.forName(it) }
    }

    /** ... and the configurations nested in them. */
    private val configurations: List<Class<*>> by lazy { autoConfigurations.flatMap { nested(it) } }

    /** A `@Bean` method, read as Spring reads it (a meta-annotation counts too). */
    private fun isBean(method: Method): Boolean = AnnotatedElementUtils.hasAnnotation(method, Bean::class.java)

    private fun nested(type: Class<*>): List<Class<*>> = listOf(type) + type.declaredClasses.flatMap { nested(it) }

    private val beanMethods: List<Method> by lazy {
        configurations.flatMap { it.declaredMethods.toList() }.filter(::isBean)
    }

    @Test
    fun `no bean method returns a check that lazy initialization would defer`() {
        val lazy = beanMethods.filter { method ->
            val type = method.returnType
            val runsOnItsOwn = InitializingBean::class.java.isAssignableFrom(type) ||
                SmartInitializingSingleton::class.java.isAssignableFrom(type)
            (runsOnItsOwn || type == Any::class.java) && !StartupTask::class.java.isAssignableFrom(type)
        }
        lazy.map { "${it.declaringClass.simpleName}.${it.name}: ${it.returnType.simpleName}" }.assert().isEmpty()
    }

    /** The exclusion is there wherever a check is, whichever auto-configurations an application excludes. */
    @Test
    fun `every auto-configuration with a check imports the exclusion`() {
        val missing = autoConfigurations.filter { configuration ->
            val checks = nested(configuration).flatMap { it.declaredMethods.toList() }
                .any { isBean(it) && StartupTask::class.java.isAssignableFrom(it.returnType) }
            val imported = configuration.getAnnotation(Import::class.java)?.value.orEmpty()
                .any { it == StartupTaskExclusion::class }
            checks && !imported
        }
        missing.map { it.simpleName }.assert().isEmpty()
    }

    /** The guard sees what it is meant to: the checks of `STA-1`, `STA-2`, `SNP-3`, `IDN-6` and the others. */
    @Test
    fun `the checks are found and are startup tasks`() {
        val checks = beanMethods.filter { StartupTask::class.java.isAssignableFrom(it.returnType) }.map { it.name }
        checks.assert().contains(
            "cosecWebMvcModuleCheck",
            "cosecUnknownPropertiesCheck",
            "cosecPipelineModeCheck",
            "cosecGatewayEndpointCheck",
            "cosecEvictionPolicyCheck",
            "cosecEvictionPolicyMonitor",
            "cosecReplicaReadCheck",
            "cosecApiKeyMembershipProviderCheck",
            "cosecControlAuthorizationCheck",
            "cosecShadowReporting",
        )
    }

    @Test
    fun `the exclusion keeps startup tasks, and only them, eager`() {
        val definition = mockk<BeanDefinition>()
        StartupTask.EAGER.isExcluded("check", definition, StartupTask.OnCreation::class.java).assert().isTrue()
        StartupTask.EAGER.isExcluded("check", definition, StartupTask.AfterSingletons::class.java).assert().isTrue()
        StartupTask.EAGER.isExcluded("other", definition, InitializingBean::class.java).assert().isFalse()
        StartupTask.EAGER.isExcluded("other", definition, String::class.java).assert().isFalse()
    }
}
