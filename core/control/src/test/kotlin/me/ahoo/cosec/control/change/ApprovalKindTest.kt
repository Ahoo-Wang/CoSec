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

package me.ahoo.cosec.control.change

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.api.ChangeSamples
import me.ahoo.cosec.control.lifecycle.ChangeAuthority
import me.ahoo.cosec.spi.identity.Identity
import me.ahoo.cosec.spi.token.RevocationReader
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * `APR-9`: an approval records whose it was, and publication counts it only when it says a person's. One recorded before
 * kinds says nothing, and one a host read as a workload's says so: neither counts, and the change is approved again.
 */
class ApprovalKindTest {
    private val change = ChangeSamples.full
    private val authority = ChangeAuthority(PERMIT_ALL, revocations = RevocationReader.NONE)

    private fun problem(kind: Identity.Credential.Kind?): String? = runBlocking {
        val approval = checkNotNull(change.approval).copy(kind = kind)
        authority.approvals.approverProblem(change.copy(approval = approval), change.escalations, PLATO)
    }

    @Test
    fun `an approval counts only when it says its approver was a person - APR-9`() {
        listOf(null, Identity.Credential.Kind.WORKLOAD).forEach { kind ->
            problem(kind).assert().describedAs("$kind")
                .isEqualTo("the approval does not say its approver was a person: approve the change again")
        }
        // A person's approval passes this check, and is judged by the rest: here, a publisher the sample cannot check.
        problem(Identity.Credential.Kind.PERSON).assert()
            .isNotEqualTo("the approval does not say its approver was a person: approve the change again")
    }

    @Test
    fun `a stored approval keeps its kind, and one recorded before kinds reads as none - APR-9`() {
        Identity.Credential.Kind.entries.forEach { kind ->
            val stored = change.copy(approval = change.approval!!.copy(kind = kind))
            ChangeRequestCodec.decode(ChangeRequestCodec.encode(stored)).approval!!.kind.assert().isEqualTo(kind)
        }
        val before = change.copy(approval = change.approval!!.copy(kind = null))
        val encoded = ChangeRequestCodec.encode(before).decodeToString()
        encoded.assert().doesNotContain("\"kind\":\"PERSON\"").doesNotContain("\"kind\":\"WORKLOAD\"")
        ChangeRequestCodec.decode(encoded.encodeToByteArray()).approval!!.kind.assert().isNull()
    }
}
