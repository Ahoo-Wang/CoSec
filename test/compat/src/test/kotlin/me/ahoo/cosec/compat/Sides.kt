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

package me.ahoo.cosec.compat

import java.io.File
import java.net.URLClassLoader
import java.util.function.BiFunction

/**
 * One version of CoSec, loaded in a class loader of its own whose parent is the platform's: nothing of the test's own
 * classpath, the current build's included, is visible to it, so the previous release runs with exactly its published
 * classes and their own dependencies.
 */
class Side private constructor(val name: String, private val side: BiFunction<String, Map<String, String>, String>) {
    /** [operation] with [arguments], as this version answers it. */
    operator fun invoke(operation: String, vararg arguments: Pair<String, String>): String =
        side.apply(operation, arguments.toMap())

    override fun toString(): String = name

    companion object {
        private fun load(name: String, property: String): Side {
            val classpath = checkNotNull(
                System.getProperty(property)
            ) { "$property is not set: run the tests through Gradle." }
            val urls = classpath.split(File.pathSeparator).filter { it.isNotBlank() }.map { File(it).toURI().toURL() }
            val loader = URLClassLoader("cosec-$name", urls.toTypedArray(), ClassLoader.getPlatformClassLoader())

            @Suppress("UNCHECKED_CAST")
            val side = loader.loadClass("me.ahoo.cosec.compat.side.Side").getConstructor().newInstance()
                as BiFunction<String, Map<String, String>, String>
            return Side(name, side)
        }

        /** The current build. */
        val CURRENT: Side by lazy { load("current", "cosec.compat.current") }

        /** The previous release (`cosec-previous`). */
        val PREVIOUS: Side by lazy { load("previous", "cosec.compat.previous") }
    }
}
