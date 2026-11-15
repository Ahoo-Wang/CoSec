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
 * RFC 0013 §4.2: each field of the request state is written by the stage that owns it, read from the compiled classes.
 * `authn` has two writers, `Authenticate` and `ResolveMembers`, which rewrites it when the membership provider refuses
 * the credential; every other field has one. A new field needs its row here, so no stage writes what another decides
 * on without the table saying so.
 */
class RequestStateWriterTest {
    @Test
    fun `each field of the request state is written only by its stage`() {
        val state = SourceRulesTest.CLASSES.get(STATE)
        val setters = state.methods.filter { it.name.startsWith("set") && it.rawParameterTypes.size == 1 }
        setters.map { it.name }.toSet().assert().isEqualTo(WRITERS.keys)
        val wrong = setters.flatMap { setter ->
            setter.callsOfSelf.map { it.originOwner.name.substringBefore('$').substringAfterLast('.') }
                .filterNot { it in WRITERS.getValue(setter.name) }
                .map { "${setter.name} by $it" }
        }
        wrong.assert().describedAs("request state written outside its stage").isEmpty()
        // A writer that writes nothing would pass vacuously.
        setters.filter { it.callsOfSelf.isEmpty() }.map { it.name }.assert().isEmpty()
    }

    private companion object {
        const val STATE = "me.ahoo.cosec.runtime.stage.RequestState"
        val WRITERS: Map<String, Set<String>> = mapOf(
            "setFacts" to setOf("ReadRequest"),
            "setPath" to setOf("ReadRequest"),
            "setParameters" to setOf("ReadRequest"),
            "setAction" to setOf("ReadRequest"),
            "setResource" to setOf("ReadRequest"),
            "setAuthn" to setOf("Authenticate", "ResolveMembers"),
            "setFetched" to setOf("ResolveMembers"),
            "setSubject" to setOf("ResolveMembers"),
            "setMemberships" to setOf("ResolveMembers"),
            "setScope" to setOf("ResolveScope"),
            "setBlacklist" to setOf("LoadView"),
            "setView" to setOf("LoadView"),
            "setRequest" to setOf("FetchAttributes"),
            "setDecision" to setOf("Evaluate"),
            "setEvaluationTrace" to setOf("Evaluate"),
            "setBreakGlass" to setOf("Evaluate"),
            "setTightest" to setOf("RunObligations"),
            "setSecurity" to setOf("Respond"),
            "setRequiredFailures" to setOf("DeliverRequired"),
        )
    }
}
