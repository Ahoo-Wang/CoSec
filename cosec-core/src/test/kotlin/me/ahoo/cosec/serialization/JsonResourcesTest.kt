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

package me.ahoo.cosec.serialization

import me.ahoo.cosec.api.policy.Policy
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.io.File
import java.net.URLClassLoader

class JsonResourcesTest {
    private val emptyClasspath = PathMatchingResourcePatternResolver(URLClassLoader(arrayOf(), null))

    @Test
    fun fileMatchedByTwoLocationsIsResolvedOnce() {
        val locations = listOf("classpath:cosec-policy/test-policy.json", "classpath:cosec-policy/*-policy.json")
        JsonResources.resources(locations).map { it.filename }.assert().containsExactly("test-policy.json")
    }

    @Test
    fun locationThatDoesNotExistFails() {
        assertThrownBy<IllegalStateException> {
            JsonResources.resources(listOf("classpath:not-exists"), "Thing")
        }.hasMessage("Thing location [classpath:not-exists] matches no file.")
    }

    @Test
    fun patternMatchingNoFileFails(@TempDir dir: File) {
        assertThrownBy<IllegalStateException> {
            JsonResources.resources(listOf("file:${dir.absolutePath}/*.json"))
        }.hasMessageContaining("matches no file")
    }

    @Test
    fun onlyOptionalLocationsMayMatchNoFile() {
        val default = "classpath:defaults/*.json"
        JsonResources.resources(listOf(default), optionalLocations = setOf(default), resolver = emptyClasspath)
            .assert().isEmpty()
        assertThrownBy<IllegalStateException> {
            JsonResources.resources(
                listOf(default, "classpath:other/*.json"),
                optionalLocations = setOf(default),
                resolver = emptyClasspath,
            )
        }.hasMessageContaining("[classpath:other/*.json]")
    }

    @Test
    fun readAllRejectsDuplicateIdsAcrossFiles(@TempDir dir: File) {
        val policy = File(javaClass.getResource("/cosec-policy/test-policy.json")!!.toURI()).readText()
        dir.resolve("a.json").writeText(policy)
        dir.resolve("b.json").writeText(policy)
        val locations = listOf("file:${dir.absolutePath}/*.json")
        JsonResources.readAll(locations, Policy::class.java).assert().hasSize(2)
        assertThrownBy<IllegalStateException> {
            JsonResources.readAll(locations, Policy::class.java) { it.id }
        }.hasMessageContaining("Policy id [id] is declared more than once").hasMessageContaining("a.json")
            .hasMessageContaining("b.json")
    }
}
