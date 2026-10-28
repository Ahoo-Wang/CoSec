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

package me.ahoo.cosec.control.change

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.MutableClock
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.model.condition.Attribute
import me.ahoo.cosec.model.condition.AttributeCatalog
import me.ahoo.cosec.model.condition.AttributeType
import me.ahoo.cosec.model.condition.Condition
import me.ahoo.cosec.model.condition.Literal
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/** RFC 0007 §3.1 N3 (`CTL-20`): a candidate is checked against the attributes an instance would compile it with. */
class ProviderAttributesTest {
    private val clock = MutableClock()
    private val store = InMemoryControlStore(clock)
    private val revisions = Revisions(store, clock)
    private val ids = AtomicInteger()
    private val changes = Changes(
        store,
        revisions,
        { ChangeId("c${ids.incrementAndGet()}") },
        PERMIT_ALL,
        clock,
        attributes = AttributeCatalog.of(listOf(Attribute.external("ext.geo.country", AttributeType.STRING))),
    )

    private fun on(attribute: String) = runBlocking {
        changes.propose(
            ALICE_OP,
            ACME,
            0,
            listOf(Operation.PutPolicy(allow("geo", condition = Condition.Eq(attribute, Literal.Str("NZ"))))),
            "geo",
        )
    }

    @Test
    fun `a candidate compiles against the platform's and the providers' attributes`() {
        on("ext.geo.country").state.assert().isEqualTo(ChangeState.CHECKED)
        on("ext.geo.city").errors.map { it.check }.assert().containsExactly("COMPILE")
    }
}
