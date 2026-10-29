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

package me.ahoo.cosec.spring.boot.starter.cosid

import com.auth0.jwt.JWT
import me.ahoo.cosec.api.principal.SimplePrincipal
import me.ahoo.cosec.cosid.CosIdIdGenerator
import me.ahoo.cosec.login.token.TokenConverter
import me.ahoo.cosec.spi.id.IdGenerator
import me.ahoo.cosec.spi.token.TokenStore
import me.ahoo.cosec.spring.boot.starter.UnknownProperties
import me.ahoo.cosec.spring.boot.starter.authentication.CoSecAuthenticationAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.jwt.CoSecJwtAutoConfiguration
import me.ahoo.cosec.spring.boot.starter.jwt.JwtProperties
import me.ahoo.cosid.spring.autoconfigure.CosIdAutoConfiguration
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.mock.env.MockEnvironment

/** CoSec's ids from the application's CosId runtime, and only when it has one and no IdGenerator of its own. */
class CoSecCosIdAutoConfigurationTest {
    private val cosId = arrayOf(
        "cosid.namespace=cosec-test",
        "cosid.machine.static.slot=1",
        "cosid.generators.default.strategy=snowflake",
        "cosid.generators.default.codec[0].radix.radix=62",
        "cosid.generators.trace.strategy=snowflake",
        "cosid.generators.trace.codec[0].radix.radix=62",
        "cosid.generators.trace.codec[1].prefix=T",
        "cosid.generators.order.strategy=snowflake",
        "cosid.generators.order.codec[0].prefix=order:",
    )

    // Listed before CosId's on purpose: the auto-configuration orders itself after it.
    private val runner = ApplicationContextRunner().withConfiguration(
        AutoConfigurations.of(CoSecCosIdAutoConfiguration::class.java, CosIdAutoConfiguration::class.java),
    )

    @Test
    fun `the ids come from CosId's default generator`() {
        runner.withPropertyValues(*cosId).run { context ->
            assertThat(context).hasSingleBean(IdGenerator::class.java)
            val ids = context.getBean(IdGenerator::class.java)
            ids.assert().isInstanceOf(CosIdIdGenerator::class.java)
            Regex("[A-Za-z0-9]{1,11}").matches(ids.generate()).assert().isTrue()
        }
    }

    @Test
    fun `cosec cosid generator names another generator`() {
        runner.withPropertyValues(*cosId, "${CosIdGeneratorProperties.GENERATOR_KEY}=trace").run { context ->
            context.getBean(IdGenerator::class.java).generate().assert().startsWith("T")
        }
    }

    @Test
    fun `a generator whose ids are not plain fails startup`() {
        runner.withPropertyValues(*cosId, "${CosIdGeneratorProperties.GENERATOR_KEY}=order").run { context ->
            assertThat(context).hasFailed()
        }
    }

    @Test
    fun `without cosec-cosid CoSec keeps its own default`() {
        runner.withPropertyValues(*cosId)
            .withClassLoader(FilteredClassLoader(CosIdIdGenerator::class.java))
            .run { context -> assertThat(context).hasNotFailed().doesNotHaveBean(IdGenerator::class.java) }
    }

    @Test
    fun `without a CosId runtime CoSec keeps its own default`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CoSecCosIdAutoConfiguration::class.java))
            .run { context -> assertThat(context).hasNotFailed().doesNotHaveBean(IdGenerator::class.java) }
    }

    @Test
    fun `the application's own IdGenerator wins`() {
        runner.withPropertyValues(*cosId)
            .withBean(IdGenerator::class.java, { IdGenerator { "application-id" } })
            .run { context -> context.getBean(IdGenerator::class.java).generate().assert().isEqualTo("application-id") }
    }

    @Test
    fun `nothing when CoSec is off`() {
        runner.withPropertyValues(*cosId, "cosec.enabled=false").run { context ->
            assertThat(context).hasNotFailed().doesNotHaveBean(IdGenerator::class.java)
        }
    }

    /** The starter fails startup on a cosec.* key no metadata names: its metadata names this one. */
    @Test
    fun `cosec cosid generator is a key CoSec reads`() {
        val environment = MockEnvironment().withProperty(CosIdGeneratorProperties.GENERATOR_KEY, "trace")
        UnknownProperties.load(javaClass.classLoader).of(environment).assert().isEmpty()
    }

    /** The starter's beans take the bean as they take an application's: here the token ids. */
    @Test
    fun `the starter's token ids come from CosId`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(CoSecCosIdAutoConfiguration::class.java, CosIdAutoConfiguration::class.java),
            )
            .withUserConfiguration(
                CoSecAuthenticationAutoConfiguration::class.java,
                CoSecJwtAutoConfiguration::class.java,
            )
            .withBean(TokenStore::class.java, { TokenStore.NoOp })
            .withPropertyValues(
                *cosId,
                "${CosIdGeneratorProperties.GENERATOR_KEY}=trace",
                "${JwtProperties.PREFIX}.secret=FyN0Igd80Gas8stTavArGKOYnS9uLwGA_",
            )
            .run { context ->
                val token = context.getBean(TokenConverter::class.java).toToken(SimplePrincipal("alice"))
                JWT.decode(token.accessToken).id.assert().startsWith("T")
            }
    }
}
