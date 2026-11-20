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

package me.ahoo.cosec.cocache.control

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.shadow.InstanceIndexCodec
import me.ahoo.cosec.cocache.shadow.InstanceStatusCodec
import me.ahoo.cosec.cocache.shadow.ReportersCodec
import me.ahoo.cosec.control.store.ControlStoreException
import me.ahoo.cosec.spi.snapshot.SnapshotFormatException
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import org.junit.jupiter.api.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * CTL-52 (R2C-6): what a command to the store fails with is said as the port says it. A value that does not decode is
 * a format error, a 500; anything else is the store not answering, a 503. Nothing is a 400.
 */
class StoreFailureTest {
    private fun failing(error: RuntimeException): Throwable? = runCatching {
        runBlocking { ConditionalStores.answering<Unit>("[k]") { throw error } }
    }.exceptionOrNull()

    @Test
    fun `an outage is the store not answering, with what failed as its cause`() {
        val cause = IllegalStateException("Redis is down")
        val error = failing(cause)
        error.assert().isInstanceOf(ControlStoreException::class.java)
        error!!.cause.assert().isSameAs(cause)
        error.message.assert().contains("[k]")
    }

    @Test
    fun `a format error stays one, also when a cache wrapped it`() {
        val format = SnapshotFormatException("not a change")
        failing(format).assert().isSameAs(format)
        val wrapped = IllegalStateException("decoding", format)
        failing(wrapped).assert().isSameAs(wrapped)
    }

    @Test
    fun `the store not answering and a cancellation pass through as they are`() {
        val unavailable = ControlStoreException("[inner]: down")
        failing(unavailable).assert().isSameAs(unavailable)
        val cancelled = CancellationException("cancelled")
        failing(cancelled).assert().isSameAs(cancelled)
    }

    @Test
    fun `a malformed shadow value is a format error, never an argument error`() {
        assertThrownBy<SnapshotFormatException> { ReportersCodec.decode("""{"i1":1}""".encodeToByteArray()) }
        assertThrownBy<SnapshotFormatException> { ReportersCodec.decode("""[1]""".encodeToByteArray()) }
        assertThrownBy<SnapshotFormatException> { InstanceIndexCodec.decode("""["i1"]""".encodeToByteArray()) }
        assertThrownBy<SnapshotFormatException> { InstanceIndexCodec.decode("""{"i1":1}""".encodeToByteArray()) }
        assertThrownBy<SnapshotFormatException> { InstanceStatusCodec.decode("""{}""".encodeToByteArray()) }
    }
}
