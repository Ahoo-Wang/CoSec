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

package me.ahoo.cosec.clienthost

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.control.store.Publication
import me.ahoo.cosec.control.store.Revisions
import me.ahoo.cosec.document.Actor
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.document.ProvenanceKind
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.server.reactive.context.ReactiveWebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** The host signs in whoever names themselves: it listens on the loopback interface only. */
internal const val ADDRESS = "127.0.0.1"

private const val READY_TIMEOUT_MILLIS = 30_000L

/**
 * A real CoSec 8 host (the starter over WebFlux, CoCache in memory) with [CLIENT_HOST_DOCUMENT] published, running until
 * closed. Ready when its pipeline answers with the document: the snapshot source installs it after it is published.
 *
 * Never an example of a production host (see [ClientHostApi]): it is what the TypeScript client is tested against.
 */
internal class ClientHost private constructor(private val context: ConfigurableApplicationContext) : AutoCloseable {
    val port: Int = checkNotNull((context as ReactiveWebServerApplicationContext).webServer) {
        "The host runs no web server."
    }.port

    val url: String get() = "http://$ADDRESS:$port"

    override fun close() = context.close()

    companion object {
        /** Starts the host on [port] (0 for a free one). */
        fun start(port: Int = 0): ClientHost {
            val context = SpringApplicationBuilder(ClientHostApplication::class.java)
                .web(WebApplicationType.REACTIVE)
                .properties(
                    "server.port=$port",
                    "server.address=$ADDRESS",
                    "cocache.backend=memory",
                    "cosec.jwt.secret=client-host-secret-client-host-secret",
                    "cosec.authentication.login.refresh.path=$REFRESH_PATH",
                    "cosec.authentication.login.logout.path=$LOGOUT_PATH",
                    "cosec.authorization.routes.location=",
                    "cosec.authorization.snapshot.revalidate-interval=1s",
                    "spring.main.banner-mode=off",
                )
                .run()
            val host = ClientHost(context)
            var ready = false
            try {
                host.publish()
                host.awaitReady()
                ready = true
            } finally {
                if (!ready) context.close()
            }
            return host
        }
    }

    private fun publish() = runBlocking {
        val revisions = context.getBean(Revisions::class.java)
        revisions.publish(
            SnapshotKey.Platform,
            revisions.head(SnapshotKey.Platform),
            PolicyDocumentCodec.decode(DocumentKind.PLATFORM, "", CLIENT_HOST_DOCUMENT.encodeToByteArray()),
            Publication("client-host", ProvenanceKind.BOOTSTRAP, Actor(TenantId.PLATFORM, PrincipalId("client-host"))),
        )
    }

    private fun awaitReady() {
        val client = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder(URI.create("$url/hello")).timeout(Duration.ofSeconds(2)).build()
        val deadline = System.nanoTime() + READY_TIMEOUT_MILLIS * 1_000_000
        while (client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() != 200) {
            check(
                System.nanoTime() < deadline
            ) { "The host did not serve its document within $READY_TIMEOUT_MILLIS ms." }
            Thread.sleep(200)
        }
    }
}

/**
 * Starts the host and runs until its standard input ends or says `stop`: `typescript/scripts/with-host.mjs` owns the
 * process, and closing its end of the pipe is how it stops the host whatever happened to the test run.
 * The first argument is the port, none for a free one. Prints `READY <port>` once the host serves.
 */
fun main(args: Array<String>) {
    ClientHost.start(args.firstOrNull()?.toInt() ?: 0).use { host ->
        println("READY ${host.port}")
        generateSequence(::readlnOrNull).firstOrNull { it.trim() == "stop" }
    }
}
