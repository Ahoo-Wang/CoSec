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

@file:OptIn(ExperimentalCoSecApi::class)

package me.ahoo.cosec.control.http

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.control.sdk.ControlCaller
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * `CTL-82`: a control request has no deadline of its own; its caller's reaches the host by cancellation. A caller whose
 * coroutine is cancelled while [HttpControlService] waits for the answer takes the exchange with it: the connection the
 * request went over is closed, so the host sees its request end, instead of the request running there until the call's
 * timeout (30 s by default) ends it. The server is a bare socket that reads the request and never answers: what it sees
 * is the connection, nothing an HTTP server library would interpret.
 */
@Timeout(value = 25, unit = TimeUnit.SECONDS)
class HttpCallCancellationTest {
    private val socket = ServerSocket(0, 0, InetAddress.getLoopbackAddress())

    /** Counted down once the server has read a request's head. */
    private val requested = CountDownLatch(1)

    /** Counted down once the server sees the client's end of the connection close. */
    private val closed = CountDownLatch(1)

    private val server = thread(isDaemon = true, name = "never-answers") {
        try {
            socket.accept().use { connection ->
                val input = connection.getInputStream()
                var tail = 0
                // The request's head ends with an empty line; a GET carries no body.
                while (tail != HEAD_END) {
                    val byte = input.read()
                    if (byte < 0) return@thread
                    tail = (tail shl 8 or byte) and 0xFFFFFFFF.toInt()
                }
                requested.countDown()
                // Never answered: the next read ends only when the client closes or resets the connection.
                try {
                    while (input.read() >= 0) {
                        // A client sends nothing more on an HTTP/1.1 request it waits on.
                    }
                } catch (_: IOException) {
                    // A reset is the client's end too.
                }
                closed.countDown()
            }
        } catch (_: IOException) {
            // The socket closed by the test's end.
        }
    }

    @AfterEach
    fun stop() {
        socket.close()
        server.join(Duration.ofSeconds(5).toMillis())
    }

    @Test
    fun `a cancelled caller closes the exchange it was waiting on - CTL-82`() = runBlocking<Unit> {
        // The default timeout: well beyond the bound below, so only the cancellation can close the connection in time.
        val service = HttpControlService(URI("http://127.0.0.1:${socket.localPort}/cosec/control/v1"))
        val call = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            service.document(ControlCaller.service("svc-iam"), SnapshotKey.Tenant("acme"))
        }
        requested.await(REQUEST_SENT_WITHIN.toMillis(), TimeUnit.MILLISECONDS).assert()
            .describedAs("the request reached the server").isTrue()

        call.cancelAndJoin()

        call.isCancelled.assert().isTrue()
        closed.await(CLOSED_WITHIN.toMillis(), TimeUnit.MILLISECONDS).assert()
            .describedAs("the connection was closed once the caller went away, not left to the call's timeout")
            .isTrue()
    }

    private companion object {
        /** `\r\n\r\n`, the end of a request's head, as the last four bytes read. */
        const val HEAD_END = 0x0D0A0D0A

        /** How long a call on loopback may take to reach the server, a cold JVM's first call included. */
        val REQUEST_SENT_WITHIN: Duration = Duration.ofSeconds(10)

        /** How long the client may take to close the connection of a cancelled call: a third of its default timeout. */
        val CLOSED_WITHIN: Duration = HttpControlService.DEFAULT_TIMEOUT.dividedBy(3)
    }
}
