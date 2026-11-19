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

package me.ahoo.cosec.system

import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** One request and its answer, as the network carried them. */
class Exchange(
    val method: String,
    val uri: URI,
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: String,
) {
    /** The first value of a response header, matched ignoring case. */
    fun header(name: String): String? = headers.entries.firstOrNull {
        it.key.equals(
            name,
            ignoreCase = true
        )
    }?.value?.firstOrNull()

    @Suppress("UNCHECKED_CAST")
    fun json(): Map<String, Any?> = Json.mapper.readValue(body, Map::class.java) as Map<String, Any?>

    override fun toString(): String = "$method $uri -> $status ${header("Content-Type").orEmpty()} $body"
}

object Json {
    val mapper: JsonMapper = JsonMapper.builder().build()
}

/**
 * The scenarios' only way into the system: plain HTTP/1.1, no redirects, no client that knows CoSec. Every exchange goes
 * into the [Transcript], request headers included (the credentials are the workflow's throwaway ones).
 */
object Http {
    private val client: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    fun get(uri: URI, vararg headers: Pair<String, String>): Exchange = send("GET", uri, headers.toList(), null)

    fun send(method: String, uri: URI, headers: List<Pair<String, String>>, body: String?): Exchange {
        val builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(10))
            .method(method, body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody())
        headers.forEach { (name, value) -> builder.header(name, value) }
        Transcript.note(
            "> $method $uri ${headers.joinToString { "${it.first}: ${it.second}" }}${body?.let { " $it" }.orEmpty()}"
        )
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        val exchange = Exchange(method, uri, response.statusCode(), response.headers().map(), response.body())
        Transcript.note("< ${exchange.status} ${exchange.headers} ${exchange.body}")
        return exchange
    }
}
