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

dependencies {
    // The bridge to the coroutine pipeline (RFC 0006 §7.3): mono {} from kotlinx-coroutines-reactor.
    api(project(":runtime"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    api("io.projectreactor.kotlin:reactor-kotlin-extensions")
    api("org.springframework:spring-context")
    api("org.springframework:spring-webflux")
    testImplementation("org.springframework:spring-test")
    testImplementation("io.projectreactor:reactor-test")
}
