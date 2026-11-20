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

package me.ahoo.cosec.migrate

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.store.InMemoryControlStore
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test

/**
 * R2C-21: `bootstrap` run again for the principal it already bound, by a deployment job on every start or after a lost
 * reply, answers that it is bound and writes nothing; it refuses only another principal, unless forced.
 */
class BootstrapAgainTest {
    private val store = InMemoryControlStore()
    private val alice = PrincipalId("alice")

    @Test
    fun `a second run for the same principal answers the revision it is bound at, and writes nothing`() {
        val bootstrap = Bootstrap(store)
        bootstrap.bound(alice).assert().isNull()
        val revision = bootstrap.run(alice)
        bootstrap.bound(alice).assert().isEqualTo(revision)
        bootstrap.run(alice).assert().isEqualTo(revision)
        runBlocking { Revisions(store).head(Bootstrap.KEY)!!.revision }.assert().isEqualTo(revision)
    }

    @Test
    fun `another principal is refused, unless forced`() {
        val bootstrap = Bootstrap(store)
        bootstrap.run(alice)
        bootstrap.bound(PrincipalId("bob")).assert().isNull()
        assertThrownBy<BootstrapRefusedException> { bootstrap.run(PrincipalId("bob")) }
            .hasMessageContaining("another principal")
        bootstrap.run(PrincipalId("bob"), force = true).assert().isEqualTo(2)
    }
}
