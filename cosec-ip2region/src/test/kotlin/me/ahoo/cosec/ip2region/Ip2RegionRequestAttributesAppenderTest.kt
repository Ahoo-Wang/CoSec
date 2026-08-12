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

package me.ahoo.cosec.ip2region

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.cosec.api.context.request.Request
import me.ahoo.cosec.context.request.RequestAttributesAppender
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.InvocationTargetException
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class Ip2RegionRequestAttributesAppenderTest {
    private val ip2RegionRequestAttributesAppender = Ip2RegionRequestAttributesAppender()

    @Test
    fun append() {
        val request: Request = mockk {
            every { remoteIp } returns "101.228.87.88"
            every { mergeAttributes(mapOf(REQUEST_ATTRIBUTES_IP_REGION_KEY to "中国|0|上海|上海市|电信")) } returns mockk()
        }

        ip2RegionRequestAttributesAppender.append(request)

        verify {
            request.remoteIp
            request.mergeAttributes(mapOf(REQUEST_ATTRIBUTES_IP_REGION_KEY to "中国|0|上海|上海市|电信"))
        }
    }

    @Test
    fun appendWrongIp() {
        val request: Request = mockk {
            every { remoteIp } returns "localhost"
            every { mergeAttributes(mapOf(REQUEST_ATTRIBUTES_IP_REGION_KEY to "0|0|0|内网IP|内网IP")) } returns mockk()
        }
        ip2RegionRequestAttributesAppender.append(request)
        verify {
            request.remoteIp
            request.mergeAttributes(mapOf(REQUEST_ATTRIBUTES_IP_REGION_KEY to "0|0|0|内网IP|内网IP"))
        }
    }

    @Test
    fun appendInvalidIpReturnsOriginalRequest() {
        val request: Request = mockk {
            every { remoteIp } returns "invalid host name"
        }

        ip2RegionRequestAttributesAppender.append(request).assert().isSameAs(request)
    }

    @Test
    fun explicitDatabaseFileSupportsLookup(@TempDir tempDir: Path) {
        val database = tempDir.resolve("external-ip2region.xdb")
        requireNotNull(javaClass.classLoader.getResourceAsStream("ip2region.xdb")).use { input ->
            Files.copy(input, database)
        }
        val mergedRequest = mockk<Request>()
        val request: Request = mockk {
            every { remoteIp } returns "101.228.87.88"
            every {
                mergeAttributes(mapOf(REQUEST_ATTRIBUTES_IP_REGION_KEY to "中国|0|上海|上海市|电信"))
            } returns mergedRequest
        }

        val result = Ip2RegionRequestAttributesAppender(database.toFile()).append(request)

        result.assert().isSameAs(mergedRequest)
    }

    @Test
    fun defaultDatabaseLoadsFromPackagedJar(@TempDir tempDir: Path) {
        val jar = createPackagedJar(tempDir, includeDatabase = true)
        val request: Request = mockk {
            every { remoteIp } returns "101.228.87.88"
            every {
                mergeAttributes(mapOf(REQUEST_ATTRIBUTES_IP_REGION_KEY to "中国|0|上海|上海市|电信"))
            } returns mockk()
        }

        ChildFirstUrlClassLoader(
            arrayOf(jar.toUri().toURL()),
            javaClass.classLoader,
        ).use { classLoader ->
            val appender = classLoader
                .loadClass("me.ahoo.cosec.ip2region.Ip2RegionRequestAttributesAppender")
                .getDeclaredConstructor()
                .newInstance() as RequestAttributesAppender

            appender.append(request)
        }

        verify {
            request.mergeAttributes(mapOf(REQUEST_ATTRIBUTES_IP_REGION_KEY to "中国|0|上海|上海市|电信"))
        }
    }

    @Test
    fun defaultDatabaseFailsFastWhenPackagedResourceIsMissing(@TempDir tempDir: Path) {
        val jar = createPackagedJar(tempDir, includeDatabase = false)

        ChildFirstUrlClassLoader(
            arrayOf(jar.toUri().toURL()),
            javaClass.classLoader,
        ).use { classLoader ->
            val constructor = classLoader
                .loadClass("me.ahoo.cosec.ip2region.Ip2RegionRequestAttributesAppender")
                .getDeclaredConstructor()

            assertThrownBy<InvocationTargetException> {
                constructor.newInstance()
            }.cause()
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessage("Classpath resource [ip2region.xdb] was not found.")
        }
    }

    private fun createPackagedJar(tempDir: Path, includeDatabase: Boolean): Path {
        val jar = tempDir.resolve("packaged-ip2region.jar")
        val resourceNames = buildList {
            add("me/ahoo/cosec/ip2region/Ip2RegionRequestAttributesAppender.class")
            add("me/ahoo/cosec/ip2region/Ip2RegionRequestAttributesAppender\$Companion.class")
            add("me/ahoo/cosec/ip2region/Ip2RegionRequestAttributesAppenderKt.class")
            if (includeDatabase) {
                add("ip2region.xdb")
            }
        }
        JarOutputStream(Files.newOutputStream(jar)).use { output ->
            resourceNames.forEach { resourceName ->
                output.putNextEntry(JarEntry(resourceName))
                requireNotNull(javaClass.classLoader.getResourceAsStream(resourceName)).use { input ->
                    input.copyTo(output)
                }
                output.closeEntry()
            }
        }
        return jar
    }

    private class ChildFirstUrlClassLoader(urls: Array<URL>, parent: ClassLoader) : URLClassLoader(urls, parent) {
        override fun getResource(name: String): URL? {
            if (name == "ip2region.xdb") {
                return findResource(name)
            }
            return super.getResource(name)
        }

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (!name.startsWith("me.ahoo.cosec.ip2region.Ip2RegionRequestAttributesAppender")) {
                return super.loadClass(name, resolve)
            }
            synchronized(getClassLoadingLock(name)) {
                val loadedClass = findLoadedClass(name) ?: findClass(name)
                if (resolve) {
                    resolveClass(loadedClass)
                }
                return loadedClass
            }
        }
    }
}
