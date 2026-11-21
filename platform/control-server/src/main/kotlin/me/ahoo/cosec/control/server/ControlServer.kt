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

package me.ahoo.cosec.control.server

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

/**
 * `cosec-control-server`: the control plane as an application. It is the starter with `cosec-control` and nothing else,
 * set in `application.yaml` to decide with policies (`cosec.pipeline.mode=enforce`) while taking its caller from the
 * `CoSec-Identity` header (`cosec.pipeline.caller=forwarded-identity`, `STA-9`): it verifies no token and issues none.
 * It starts only under the operator's statement of what can reach it ([ControlServerRefusals] and the starter's own).
 */
@SpringBootApplication
class ControlServer

fun main(args: Array<String>) {
    SpringApplication.run(ControlServer::class.java, *args)
}
