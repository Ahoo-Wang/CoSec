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

package me.ahoo.cosec.spring.boot.starter.pipeline

import me.ahoo.cosec.runtime.route.RouteMap
import me.ahoo.cosec.runtime.route.RouteMapException
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.DescriptiveResource
import org.springframework.core.io.Resource
import org.springframework.core.io.ResourceLoader

/** U1: a route map is opted into; the one thing that cannot happen is a map that is there being left out in silence. */
@ExtendWith(OutputCaptureExtension::class)
class RouteMapsTest {
    private val default = RuntimeAuthorizationProperties.Routes.DEFAULT_LOCATION
    private val orders = """{"service":"order","routes":[
        {"method":"GET","path":"/orders/{id}","action":"order:read","resource":"order/{id}"}]}"""

    private fun files(vararg files: Pair<String, String>) = object : ResourceLoader {
        override fun getResource(location: String): Resource =
            files.toMap()[location]?.let { ByteArrayResource(it.encodeToByteArray()) } ?: DescriptiveResource(location)

        override fun getClassLoader(): ClassLoader? = null
    }

    @Test
    fun `unset and no default file runs without a route map and says so`(output: CapturedOutput) {
        RouteMaps.load(null, files()).assert().isSameAs(RouteMap.EMPTY)
        output.out.assert().contains("No route map")
    }

    @Test
    fun `unset beside the default file fails startup, saying how to fix it`() {
        val message = assertThrows<IllegalStateException> { RouteMaps.load(null, files(default to orders)) }.message
        message.assert().contains("cosec.authorization.routes.location is not set").contains("set it to $default")
    }

    @Test
    fun `empty runs without a route map, and warns when the default file is left out`(output: CapturedOutput) {
        RouteMaps.load("", files(default to orders)).assert().isSameAs(RouteMap.EMPTY)
        output.out.assert().contains("is empty").contains("not loaded")
    }

    @Test
    fun `a location set explicitly must exist`() {
        assertThrows<IllegalStateException> { RouteMaps.load("classpath:nowhere.json", files()) }
            .message.assert().contains("[classpath:nowhere.json]").contains("does not exist")
    }

    @Test
    fun `a location set loads and logs its service and route count`(output: CapturedOutput) {
        RouteMaps.load(default, files(default to orders)).assert().isNotSameAs(RouteMap.EMPTY)
        output.out.assert().contains("Route map [$default]: service [order], 1 routes.")
    }

    @Test
    fun `a map with no routes refuses to load`() {
        assertThrows<RouteMapException> {
            RouteMaps.load(default, files(default to """{"service":"order","routes":[]}"""))
        }.message.assert().contains("no routes")
    }
}
