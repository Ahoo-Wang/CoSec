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

package me.ahoo.cosec.limiter

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.io.File
import java.net.URLClassLoader

class LocalRateLimitRuleLoaderTest {
    @Test
    fun loadObjectsAndArrays() {
        val rules = LocalRateLimitRuleLoader(setOf("classpath:cosec-rate-limit/*-rate-limit.json")).getRules()
        rules.map { it.id }.assert().containsExactlyInAnyOrder("login-by-ip", "export-free-tier", "global-api")
        val login = rules.first { it.id == "login-by-ip" }
        login.key.assert().isEqualTo("request.remoteIp")
        login.rateLimit.assert().isEqualTo(RateLimit(permitsPerSecond = 0.1, windowSeconds = 60))
        login.strictFailure.assert().isTrue()
    }

    @Test
    fun fileMatchedByTwoLocationsIsLoadedOnce() {
        LocalRateLimitRuleLoader(
            setOf("classpath:cosec-rate-limit/*-rate-limit.json", "classpath:cosec-rate-limit/login-rate-limit.json")
        ).getRules().map { it.id }.assert().containsExactlyInAnyOrder("login-by-ip", "export-free-tier", "global-api")
    }

    @Test
    fun explicitLocationThatDoesNotExistFailsLoading() {
        assertThrownBy<IllegalStateException> {
            LocalRateLimitRuleLoader(setOf("classpath:cosec-rate-limit-not-found")).getRules()
        }.hasMessage("Rate limit rule location [classpath:cosec-rate-limit-not-found] matches no file.")
    }

    @Test
    fun explicitPatternMatchingNoFileFailsLoading(@TempDir dir: File) {
        assertThrownBy<IllegalStateException> {
            LocalRateLimitRuleLoader(setOf("classpath:not-exists/*.json")).getRules()
        }.hasMessageContaining("matches no file")
        assertThrownBy<IllegalStateException> {
            LocalRateLimitRuleLoader(setOf("file:${dir.absolutePath}/*.json")).getRules()
        }.hasMessageContaining("matches no file")
    }

    @Test
    fun defaultLocationMayMatchNoFile() {
        // This module's test classpath has cosec-rate-limit/*-rate-limit.json, so resolve against an empty class loader.
        val emptyClasspath = PathMatchingResourcePatternResolver(URLClassLoader(arrayOf(), null))
        LocalRateLimitRuleLoader(setOf(LocalRateLimitRuleLoader.DEFAULT_LOCATION), emptyClasspath).getRules()
            .assert().isEmpty()
    }

    @Test
    fun invalidRuleFailsFast() {
        assertThrownBy<IllegalStateException> {
            LocalRateLimitRuleLoader(setOf("classpath:cosec-rate-limit/invalid/*.json")).getRules()
        }.hasMessageContaining("missing-rate.json")
    }

    @Test
    fun duplicateIdsAcrossFilesFailLoading() {
        assertThrownBy<IllegalStateException> {
            LocalRateLimitRuleLoader(setOf("classpath:cosec-rate-limit/duplicate/*.json")).getRules()
        }.hasMessageContaining("Rate limit rule id [login-by-ip] is declared more than once")
            .hasMessageContaining("a.json")
            .hasMessageContaining("b.json")
    }

    @Test
    fun duplicateIdsInOneFileFailLoading(@TempDir dir: File) {
        val rule = """{ "id": "r", "action": "*", "key": "request.remoteIp", "permitsPerSecond": 1 }"""
        dir.resolve("rules.json").writeText("[$rule, $rule]")
        assertThrownBy<IllegalStateException> {
            LocalRateLimitRuleLoader(setOf("file:${dir.absolutePath}/*.json")).getRules()
        }.hasMessageContaining("Rate limit rule id [r] is declared more than once")
    }
}
