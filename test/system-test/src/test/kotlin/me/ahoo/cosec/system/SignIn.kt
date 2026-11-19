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
import java.net.URI
import java.util.Base64

/** A token pair CoSec issued at a login, with the session it started. */
class SignedIn(val accessToken: String, val refreshToken: String) {
    val bearer: Pair<String, String> get() = "Authorization" to "Bearer $accessToken"

    /** The access token's claims, read without verifying it: what the scenario compares the gateway's identity with. */
    @Suppress("UNCHECKED_CAST")
    val claims: Map<String, Any?>
        get() = Json.mapper.readValue(
            Base64.getUrlDecoder().decode(accessToken.split('.')[1]),
            Map::class.java
        ) as Map<String, Any?>
}

/**
 * Signs in at the system's login host (`test/system-auth`), through a gateway, as a client does: `POST /auth/login`. The
 * login believes whom the request names (it is a test host on a closed network); the token pair and its session are
 * CoSec's own.
 */
object SignIn {
    fun of(
        username: String,
        tenant: String,
        app: String? = null,
        roles: List<String> = emptyList(),
        gateway: URI = SystemTopology.gateway,
    ): SignedIn {
        val body = mapOf("username" to username, "tenant" to tenant, "app" to app, "roles" to roles)
        val answer = Http.post(gateway.resolve("/auth/login"), body)
        answer.status.assert().describedAs("login of $username: $answer").isEqualTo(200)
        val pair = answer.json()
        return SignedIn(pair.getValue("accessToken") as String, pair.getValue("refreshToken") as String)
    }

    /** `POST /auth/refresh` with [pair], through [gateway]: the served refresh route. */
    fun refresh(pair: SignedIn, gateway: URI = SystemTopology.gateway): Exchange =
        Http.post(
            gateway.resolve("/auth/refresh"),
            mapOf("accessToken" to pair.accessToken, "refreshToken" to pair.refreshToken)
        )

    /** `POST /auth/logout` with [pair], through [gateway]: the served logout route. */
    fun logout(pair: SignedIn, gateway: URI = SystemTopology.gateway): Exchange =
        Http.post(
            gateway.resolve("/auth/logout"),
            mapOf("accessToken" to pair.accessToken, "refreshToken" to pair.refreshToken)
        )

    /** The pair a successful refresh answered. */
    fun pairOf(answer: Exchange): SignedIn {
        answer.status.assert().describedAs("a refresh: $answer").isEqualTo(200)
        val body = answer.json()
        return SignedIn(body.getValue("accessToken") as String, body.getValue("refreshToken") as String)
    }

    /** The platform administrator `cosec-migrate bootstrap` named, for the control plane (tenant `(platform)`). */
    fun root(): SignedIn = of(ROOT, "(platform)", app = CONTROL_APP)

    const val ROOT = "root"
    const val CONTROL_APP = "cosec-control"
}
