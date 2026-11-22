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

// The control plane's facade over HTTP (CTL-77): HttpControlService calls a control-plane host's service port with the JDK's
// HttpClient, stating each call's caller in CoSec-Identity as the gateway writes it. It brings cosec-runtime: the header of a
// request's caller is the gateway's one mapping (IdentityIssuer), and a service caller's identity the one the in-process
// implementation admits (ServiceIdentity), so both implementations state a caller alike.
dependencies {
    api(project(":control-sdk"))
    implementation(project(":runtime"))
    testImplementation(project(":testkit"))
}
