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

package me.ahoo.cosec.architecture

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * A logger an operator routes is named outside every package that holds classes, and not under another routed logger.
 * Logging is a hierarchy: a class logger named after its package is a child of the package's logger, so everything it
 * logs lands in the appender of a routed logger of that name, and a level set on a parent silences the child. The names
 * are listed here because this project reads no module's classes; each is pinned to its constant by the test of the
 * class that owns it (`LoggingAuditEventSinkTest`, `LoggingChangeListenerTest`).
 */
class RoutedLoggersTest {
    private val routed = listOf("me.ahoo.cosec.audit", "me.ahoo.cosec.changelog")

    @Test
    fun `no package of any module is, or lies under, a routed logger`() {
        val clashes = routed.flatMap { name ->
            Projects.mainPackages.keys.filter { it == name || it.startsWith("$name.") }.map { "$it is under $name" }
        }
        clashes.assert().describedAs("packages that a routed logger's name shares or contains").isEmpty()
    }

    @Test
    fun `no routed logger is under another`() {
        val nested = routed.flatMap { outer ->
            routed.filter { it != outer && it.startsWith("$outer.") }.map { "$it is under $outer" }
        }
        nested.assert().isEmpty()
    }
}
