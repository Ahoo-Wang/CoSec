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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.spi.shadow

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.Properties
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * `SNP-25`: the release an instance reports is the project's version, from the resource the build writes
 * (`META-INF/cosec/release`), and it reads from a jar that holds CoSec's classes repackaged, as a fat or shaded
 * application jar does, where a manifest's `Implementation-Version` may be gone.
 */
class InstanceStatusReleaseTest {
    /** The version of the build, `gradle.properties` at the repository root (the test runs in `core/spi`). */
    private val version: String = Properties().apply {
        File("../../gradle.properties").reader().use(::load)
    }.getProperty("version")

    @Test
    fun `the release is the version of the build`() {
        InstanceStatus.RELEASE.assert().isEqualTo(version)
        InstanceStatus("i-1", java.time.Instant.EPOCH).release.assert().isEqualTo(version)
    }

    @Test
    fun `the release reads from a repackaged jar without a manifest`(@TempDir dir: Path) {
        val jar = dir.resolve("app.jar").toFile()
        JarOutputStream(jar.outputStream()).use { out ->
            for (name in listOf(CLASS, "${CLASS.removeSuffix(".class")}\$Companion.class", InstanceStatus.RELEASE_RESOURCE)) {
                out.putNextEntry(JarEntry(name))
                checkNotNull(InstanceStatus::class.java.classLoader.getResourceAsStream(name)) { name }.use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        val kotlin = File(KotlinVersion::class.java.protectionDomain.codeSource.location.toURI())
        URLClassLoader(arrayOf(jar.toURI().toURL(), kotlin.toURI().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val repackaged = loader.loadClass(InstanceStatus::class.java.name)
            repackaged.classLoader.assert().isSameAs(loader)
            repackaged.getField("RELEASE").get(null).assert().isEqualTo(version)
        }
    }

    private companion object {
        const val CLASS = "me/ahoo/cosec/spi/shadow/InstanceStatus.class"
    }
}
