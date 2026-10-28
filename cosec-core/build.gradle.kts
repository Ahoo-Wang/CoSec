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
    api(project(":cosec-api"))
    api(project(":cosec-engine"))
    api("me.ahoo.cosid:cosid-core")
    api("io.github.oshai:kotlin-logging-jvm")
    api("io.projectreactor:reactor-core")
    api("io.projectreactor.kotlin:reactor-kotlin-extensions")
    api("tools.jackson.core:jackson-databind")
    api("tools.jackson.module:jackson-module-kotlin")
    // HttpPathPatternParityTest compares the engine's path patterns with Spring's.
    testImplementation("org.springframework:spring-web")
    testImplementation("io.projectreactor:reactor-test")
    // SchemaConformanceTest validates documents against the generated JSON schemas.
    testImplementation("com.networknt:json-schema-validator")
}

val schemaDirectory = rootProject.layout.projectDirectory.dir("schema")
val generateSchemas = tasks.register<JavaExec>("generateSchemas") {
    group = "build"
    description = "Writes schema/*.schema.json from the document format descriptors."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("me.ahoo.cosec.snapshot.format.SchemaFilesKt")
    args(schemaDirectory.asFile.absolutePath)
}
val verifySchemas = tasks.register<JavaExec>("verifySchemas") {
    group = "verification"
    description = "Fails when schema/*.schema.json differs from what generateSchemas writes."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("me.ahoo.cosec.snapshot.format.SchemaFilesKt")
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
