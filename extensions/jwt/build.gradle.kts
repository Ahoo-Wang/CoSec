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
    api(project(":login"))
    implementation("tools.jackson.core:jackson-databind")
    api("com.auth0:java-jwt")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    // MembershipClaimsJwtTest resolves parsed tokens through the pipeline's MembershipResolver.
    testImplementation(project(":runtime"))
}

// PIP-25: ClientContractTest checks contract/'s token and route files in `test`, so in `check`; a changed file reruns it.
// After an intended change of what a client receives, this task rewrites them, as updateKotlinAbi rewrites a dump.
tasks.test {
    inputs.files(rootProject.fileTree("contract")).withPropertyName("contract").withPathSensitivity(PathSensitivity.RELATIVE)
}
tasks.register<Test>("updateClientContract") {
    description = "Rewrites contract/'s token and route files from what the code answers."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("me.ahoo.cosec.jwt.ClientContractTest") }
    systemProperty("cosec.contract.update", "true")
    outputs.upToDateWhen { false }
}
