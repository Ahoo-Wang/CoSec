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

package me.ahoo.cosec.compat.side

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import java.util.function.BiFunction

/**
 * One version of CoSec as the cross-version tests see it (RFC 0014 T): an operation by name, its arguments and its answer
 * as text. This source is compiled against the current build and against the previous release; each compiled copy runs
 * in a class loader of its own, so the two exchange only what the JDK defines. An operation that a version refuses
 * answers `refused:<why>`, never throws, so the tests compare two answers.
 */
class Side : BiFunction<String, Map<String, String>, String> {
    private val operations: Map<String, (Map<String, String>) -> String> =
        IdentityOperations.ALL + TokenOperations.ALL + DocumentOperations.ALL + SessionOperations.ALL +
            CredentialOperations.ALL + CounterOperations.ALL + RecordOperations.ALL + ControlStoreOperations.ALL +
            ControlApiOperations.ALL + EdgeOperations.ALL + LocalStateOperations.ALL + Engines.ALL

    override fun apply(operation: String, arguments: Map<String, String>): String {
        if (operation == "where") return where(arguments.getValue("class"))
        val run = operations[operation] ?: return "unknown:$operation"
        // Libraries look classes up through the thread's context loader (CoCache, Lettuce, Spring): this version's own.
        val thread = Thread.currentThread()
        val caller = thread.contextClassLoader
        thread.contextClassLoader = Side::class.java.classLoader
        return try {
            run(arguments)
        } catch (e: Exception) {
            "refused:" + Refusals.of(e)
        } finally {
            thread.contextClassLoader = caller
        }
    }

    /** Where this version's copy of [className] was loaded from: its jar or classes directory. */
    private fun where(className: String): String =
        Class.forName(className, false, Side::class.java.classLoader).protectionDomain.codeSource.location.toString()
}
