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
    api(platform(libs.springBootDependencies))
    api(platform(libs.springCloudDependencies))
    api(platform(libs.cosidBom))
    api(platform(libs.cocacheBom))
    api(platform(libs.justAuth))
    api(platform(libs.opentelemetryInstrumentationBom))
    constraints {
        api(libs.ognl)
        api(libs.javaJwt)
        api(libs.ip2region)
        api(libs.guava)
        api(libs.swagger)
        api(libs.jmhCore)
        api(libs.jmhGeneratorAnnprocess)
        api(libs.hamcrest)
        api(libs.mockk)
        api(libs.detektFormatting)
    }
}
