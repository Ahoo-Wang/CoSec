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

package me.ahoo.cosec.shadow

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import java.io.File
import java.net.URLClassLoader

class LocalShadowPolicyProviderTest {
    @Test
    fun load() {
        val policies = LocalShadowPolicyProvider(setOf("classpath:cosec-shadow/*-policy.json")).getPolicies()
        policies.single().name.assert().isEqualTo("Orders v2")
    }

    @Test
    fun fileMatchedByTwoLocationsIsLoadedOnce() {
        LocalShadowPolicyProvider(
            setOf("classpath:cosec-shadow/*-policy.json", "classpath*:cosec-shadow/orders-policy.json")
        ).getPolicies().single().id.assert().isEqualTo("orders")
    }

    @Test
    fun explicitLocationThatMatchesNoFileFailsLoading(@TempDir dir: File) {
        assertThrownBy<IllegalStateException> {
            LocalShadowPolicyProvider(setOf("classpath:not-exists/*-policy.json")).getPolicies()
        }.hasMessage("Shadow policy location [classpath:not-exists/*-policy.json] matches no file.")
        assertThrownBy<IllegalStateException> {
            LocalShadowPolicyProvider(setOf("file:${dir.absolutePath}/missing.json")).getPolicies()
        }.hasMessageContaining("matches no file")
        assertThrownBy<IllegalStateException> {
            LocalShadowPolicyProvider(emptySet(), setOf("classpath:not-exists/*-app-permission.json"))
                .getAppPermissions()
        }.hasMessageContaining("Shadow app permission location [classpath:not-exists/*-app-permission.json]")
    }

    @Test
    fun defaultLocationsMayMatchNoFile() {
        // This module's test classpath has cosec-shadow/ candidates, so resolve against an empty class loader.
        val emptyClasspath = PathMatchingResourcePatternResolver(URLClassLoader(arrayOf(), null))
        val provider = LocalShadowPolicyProvider(
            setOf(LocalShadowPolicyProvider.DEFAULT_LOCATION),
            setOf(LocalShadowPolicyProvider.DEFAULT_APP_PERMISSION_LOCATION),
            emptyClasspath,
        )
        provider.getPolicies().assert().isEmpty()
        provider.getAppPermissions().assert().isEmpty()
    }

    @Test
    fun invalidPolicyFailsFast() {
        assertThrownBy<IllegalStateException> {
            LocalShadowPolicyProvider(setOf("classpath:cosec-shadow/invalid/*.json")).getPolicies()
        }.hasMessageContaining("bad-policy.json")
    }

    @Test
    fun duplicatePolicyIdsAcrossFilesFailLoading(@TempDir dir: File) {
        dir.resolve("copy-policy.json").writeText(resource("/cosec-shadow/orders-policy.json"))
        assertThrownBy<IllegalStateException> {
            LocalShadowPolicyProvider(
                setOf("classpath:cosec-shadow/*-policy.json", "file:${dir.absolutePath}/*-policy.json")
            ).getPolicies()
        }.hasMessageContaining("Shadow policy id [orders] is declared more than once")
            .hasMessageContaining("copy-policy.json")
    }

    @Test
    fun duplicateAppPermissionIdsAcrossFilesFailLoading(@TempDir dir: File) {
        dir.resolve("copy-app-permission.json").writeText(resource("/cosec-shadow/shop-app-permission.json"))
        assertThrownBy<IllegalStateException> {
            LocalShadowPolicyProvider(
                setOf("classpath:cosec-shadow/*-policy.json"),
                setOf("classpath:cosec-shadow/*-app-permission.json", "file:${dir.absolutePath}/*.json"),
            ).getAppPermissions()
        }.hasMessageContaining("Shadow app permission id [shop] is declared more than once")
    }

    @Test
    fun loadAppPermissions() {
        val provider = LocalShadowPolicyProvider(
            setOf("classpath:cosec-shadow/*-policy.json"),
            setOf("classpath:cosec-shadow/*-app-permission.json"),
        )
        provider.getAppPermissions().single().id.assert().isEqualTo("shop")
        LocalShadowPolicyProvider(setOf("classpath:cosec-shadow/*-policy.json")).getAppPermissions().assert().isEmpty()
    }

    private fun resource(path: String): String = File(javaClass.getResource(path)!!.toURI()).readText()
}
