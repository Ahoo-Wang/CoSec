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
    implementation(project(":runtime"))
    implementation("io.opentelemetry.instrumentation:opentelemetry-instrumentation-api")
    // TracingListener and OpenTelemetryContextContributor (RFC 0006 §7.5, §9.5.1) serve the coroutine pipeline;
    // opentelemetry-extension-kotlin carries the OTel context across its suspensions (approved in RFC 0006 Q7).
    implementation(project(":runtime"))
    implementation("io.opentelemetry:opentelemetry-extension-kotlin")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("io.opentelemetry:opentelemetry-sdk")
}
