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

// The control plane as an application calls it (CTL-65): the ControlService interface, the caller it states, the
// refusal it throws, and (beta.6) the typed objects and the typed layer over it. Light on purpose: no runtime, no
// control plane, no Spring, no Reactor. The in-process implementation is cosec-control's, the HTTP one cosec-control-http's.
dependencies {
    api(project(":annotations"))
    // SecurityContext, which ControlCaller.of takes.
    api(project(":api"))
    // SnapshotKey, DocumentKind and coroutines.
    api(project(":spi"))
    // The one JSON mapper (CoSecJson), which reads a refusal's problem; Jackson stays internal (RFC 0011 D3).
    implementation(project(":document"))
    implementation("tools.jackson.core:jackson-databind")
}
