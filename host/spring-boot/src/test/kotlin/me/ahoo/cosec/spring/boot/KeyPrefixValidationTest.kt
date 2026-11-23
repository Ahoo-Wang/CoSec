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

import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

/**
 * `SNP-9`, DR1-D-4: a deployment's key prefix cannot nest inside another's, so it is letters, digits, `_`, `.` and `-`
 * only. A trailing `:`, which 8.0's previews tolerated, is named as such; one inside it is a migration.
 */
class KeyPrefixValidationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CoSecProperties::class)
    class Bound

    private val runner = ApplicationContextRunner().withUserConfiguration(Bound::class.java)

    @Test
    fun `a prefix of letters, digits, underscore, dot and hyphen is a prefix`() {
        for (prefix in listOf(
            "cosec",
            "acme-prod",
            "eu.west_2",
            "e2e-0f8fad5b-d9cb-469f-a165-70867728950e",
            "{cosec}"
        )) {
            CoSecProperties(keyPrefix = prefix).keysOf("snapshot").assert().isEqualTo("$prefix:snapshot")
        }
    }

    @Test
    fun `a prefix that could nest inside another's fails, saying what it holds and what to do`() {
        assertThrows<IllegalArgumentException> { CoSecProperties(keyPrefix = "cosec:") }.message.assert()
            .contains("cosec.key-prefix [cosec:]").contains("':'").contains("Remove the trailing ':' (cosec)")
        assertThrows<IllegalArgumentException> { CoSecProperties(keyPrefix = "cosec:prod") }.message.assert()
            .contains("':'").contains("move them to one without it")
        assertThrows<IllegalArgumentException> { CoSecProperties(
            keyPrefix = ""
        ) }.message.assert().contains("It is empty")
            .contains("(cosec)").contains("({cosec})")
        for (partial in listOf("{cosec}prod", "a{b}", "{cosec", "cosec}", "{co}{sec}", "{cosec:prod}", "{}")) {
            assertThrows<IllegalArgumentException> { CoSecProperties(keyPrefix = partial) }.message.assert()
                .describedAs(partial).contains("SNP-9")
        }
        for (prefix in listOf("a b", "a/b", "ünı", "a*")) {
            assertThrows<IllegalArgumentException> { CoSecProperties(keyPrefix = prefix) }.message.assert()
                .contains("SNP-9")
        }
    }

    @Test
    fun `startup fails on such a prefix, naming the property`() {
        runner.withPropertyValues("cosec.key-prefix=cosec:prod").run { context ->
            assertThat(context).hasFailed()
            generateSequence(context.startupFailure) { it.cause }.map { it.message.orEmpty() }
                .any { "cosec.key-prefix [cosec:prod]" in it }.assert().isTrue()
        }
        runner.withPropertyValues("cosec.key-prefix=acme").run { context -> assertThat(context).hasNotFailed() }
    }
}
