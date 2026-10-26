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

package me.ahoo.cosec.e2e

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A TCP relay to Redis that [stop] cuts, as an outage does: open connections are closed and new ones refused. Redis
 * itself is shared by every test on the machine, so it cannot be stopped.
 */
class RedisOutage(private val host: String = "localhost", private val port: Int = 6379) : AutoCloseable {
    private val server = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
    private val sockets = CopyOnWriteArrayList<Socket>()

    val uri: String get() = "redis://localhost:${server.localPort}"

    init {
        Thread({ accept() }, "redis-outage-accept").apply { isDaemon = true }.start()
    }

    private fun accept() {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                return
            }
            val upstream = Socket(host, port)
            sockets += client
            sockets += upstream
            pump(client, upstream)
            pump(upstream, client)
        }
    }

    private fun pump(from: Socket, to: Socket) {
        Thread({
            try {
                from.getInputStream().transferTo(to.getOutputStream())
            } catch (_: IOException) {
                // The outage, or the other side closing.
            } finally {
                from.close()
                to.close()
            }
        }, "redis-outage-pump").apply { isDaemon = true }.start()
    }

    fun stop() {
        server.close()
        sockets.forEach(Socket::close)
    }

    override fun close() = stop()
}
