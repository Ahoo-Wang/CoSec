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

import me.ahoo.test.asserts.assert

/** One request to one process, named for the transcript and the failure. */
class Probe(val name: String, val send: () -> Exchange) {
    companion object {
        /**
         * The same request at both gateways (under `/behind`) and at the enforcing service: [path] with each of
         * [headers]. The enforcer serves the app `orders`, so its caller's token is [enforcerHeaders]'s.
         */
        fun everywhere(
            path: String,
            headers: List<Pair<String, String>>,
            enforcerHeaders: List<Pair<String, String>> = headers,
        ): List<Probe> = SystemTopology.gateways.mapIndexed { index, gateway ->
            Probe("gw-${index + 1} $path") { Http.send("GET", gateway.resolve("/behind$path"), headers, null) }
        } + Probe("enforcer $path") { Http.send("GET", SystemTopology.enforcer.resolve(path), enforcerHeaders, null) }
    }
}

/** How many requests, across the probes, must keep the answer once it showed. */
private const val STABLE_REQUESTS = 20

/**
 * Waits, within [bound], until every probe answers as [expected]; then sends at least 20 more requests round-robin over
 * the probes, each of which must answer so too: a change shows at every process and stays.
 */
fun convergeEverywhere(bound: Bound, what: String, probes: List<Probe>, expected: (Exchange) -> Boolean) {
    for (probe in probes) {
        eventually(bound, "$what at ${probe.name}", probe.send, expected)
    }
    val rounds = (STABLE_REQUESTS + probes.size - 1) / probes.size
    repeat(rounds) { round ->
        for (probe in probes) {
            val answer = probe.send()
            expected(
                answer
            ).assert().describedAs("$what at ${probe.name}, $round rounds after it showed: $answer").isTrue()
        }
    }
}
