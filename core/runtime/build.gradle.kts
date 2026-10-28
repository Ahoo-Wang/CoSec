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

// The coroutine request pipeline (RFC 0006 §5.1), the snapshot source and the policy documents (RFC 0011 §3). Jackson is
// an implementation detail here (RFC 0011 D3); Reactor and Spring never are (§5).
dependencies {
    // Guava: the modules whose main code uses it declare it; the root build adds it to none.
    implementation("com.google.guava:guava")
    api(project(":engine"))
    api(project(":spi"))
    api(project(":annotations"))
    api("me.ahoo.cosid:cosid-core")
    api("io.github.oshai:kotlin-logging-jvm")
    implementation("tools.jackson.core:jackson-databind")
    implementation("tools.jackson.module:jackson-module-kotlin")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    // MdcContextContributor (RFC 0006 §7.5): restores the bridging thread's MDC when the pipeline resumes.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    // SnapshotColdLoadTest runs loads on Reactor's non-blocking threads, which stand in for the Netty event loop.
    testImplementation("io.projectreactor:reactor-core")
    // HttpPathPatternParityTest compares the engine's path patterns with Spring's.
    testImplementation("org.springframework:spring-web")
    // SchemaConformanceTest validates documents against the generated JSON schemas, ProblemSchemaTest encoded problems.
    testImplementation("com.networknt:json-schema-validator")
}

val schemaDirectory = rootProject.layout.projectDirectory.dir("schema")
val generateSchemas = tasks.register<JavaExec>("generateSchemas") {
    group = "build"
    description = "Writes schema/*.schema.json from the document format descriptors."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("me.ahoo.cosec.runtime.document.format.SchemaFilesKt")
    args(schemaDirectory.asFile.absolutePath)
}
val verifySchemas = tasks.register<JavaExec>("verifySchemas") {
    group = "verification"
    description = "Fails when schema/*.schema.json differs from what generateSchemas writes."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("me.ahoo.cosec.runtime.document.format.SchemaFilesKt")
    args(schemaDirectory.asFile.absolutePath, "--verify")
    inputs.dir(schemaDirectory)
    inputs.files(sourceSets["test"].runtimeClasspath)
    // An up-to-date check needs an output; an empty marker records the last successful run. The inputs above (the
    // schema files and the classpath holding the descriptors) decide whether it runs again.
    val marker = layout.buildDirectory.file("verifySchemas/ok")
    outputs.file(marker)
    doLast { marker.get().asFile.apply { parentFile.mkdirs() }.writeText("") }
}
tasks.named("check") {
    dependsOn(verifySchemas)
}

// SchemaConformanceTest reads every document of the repository; the build says where it is (RFC 0011: no path assumed).
tasks.named<Test>("test") {
    systemProperty("cosec.repository.root", rootProject.layout.projectDirectory.asFile.absolutePath)
}
