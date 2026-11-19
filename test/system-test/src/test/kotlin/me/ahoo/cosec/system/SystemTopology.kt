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

import java.net.URI
import java.nio.file.Path

/**
 * Where the stack of `test/system-test/compose.yaml` listens, as the system-test workflow (or whoever runs `systemTest`) says
 * in the environment. Nothing here has a default: a scenario run against the wrong stack would prove nothing.
 */
object SystemTopology {
    /** The first gateway replica, the image under test. */
    val gateway: URI get() = URI.create(env("COSEC_SYSTEM_GATEWAY_URL").trimEnd('/'))

    /** The Redis every process shares, as `cosec-migrate` reaches it from the runner. */
    val redis: String get() = env("COSEC_SYSTEM_REDIS_URL")

    /** The `cosec-migrate` start script built from the commit under test, as an absolute path. */
    val migrate: Path get() = Path.of(env("COSEC_SYSTEM_MIGRATE"))

    /** The gateways' HMAC secret: a throwaway value of the workflow's, never a deployment's. */
    val jwtSecret: String get() = env("COSEC_SYSTEM_JWT_SECRET")

    val contract: Path get() = Path.of(property("cosec.system.contract"))

    val documents: Path get() = Path.of(property("cosec.system.documents"))

    val transcripts: Path get() = Path.of(property("cosec.system.transcripts"))

    private fun env(name: String): String = System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: error("$name is not set: the scenarios run against the stack of test/system-test/compose.yaml (test/system-test/README.md)")

    private fun property(name: String): String = System.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: error("$name is not set: run the scenarios through Gradle (:system-test:systemTest)")
}
