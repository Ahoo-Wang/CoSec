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

// Pure data types of the decision engine (RFC 0002 §2). Kotlin standard library only: the root build leaves out the
// library defaults (platform, SLF4J) and verifyPureClasspath fails on anything else.

dependencies {
    // The stability markers, annotation classes with no code: @InternalCoSecApi must be visible to every consumer, or a
    // marked type would compile without the opt-in that says it is internal.
    api(project(":annotations"))
    // The contracts an application reads (cosec-api, pure as this module): the default tenant is defined there once,
    // as the String a SecurityContext hands out, and TenantId.DEFAULT is that value.
    api(project(":api"))
}
