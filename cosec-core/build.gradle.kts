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

plugins {
    `java-test-fixtures`
}

dependencies {
    api(project(":cosec-api"))
    api(project(":cosec-engine"))
    api("me.ahoo.cosid:cosid-core")
    compileOnly("ognl:ognl")
    compileOnly("org.springframework:spring-web")
    compileOnly("org.springframework:spring-expression")
    api("io.github.oshai:kotlin-logging-jvm")
    api("io.projectreactor:reactor-core")
    api("io.projectreactor.kotlin:reactor-kotlin-extensions")
    api("tools.jackson.core:jackson-databind")
    api("tools.jackson.module:jackson-module-kotlin")
    testImplementation("ognl:ognl")
    testImplementation("org.springframework:spring-web")
    testImplementation("org.springframework:spring-expression")
    testImplementation("io.projectreactor:reactor-test")

    testFixturesImplementation(platform(project(":cosec-dependencies")))
    // Path matchers parse with Spring's PathPatternParser; PolicyTestKit reads suites through its resource resolver.
    testFixturesImplementation("org.springframework:spring-web")
    // Replayed policies may hold SpEL or OGNL conditions.
    testFixturesRuntimeOnly("ognl:ognl")
    testFixturesRuntimeOnly("org.springframework:spring-expression")
    testFixturesRuntimeOnly("ch.qos.logback:logback-classic")
}

/*
 * Test fixtures hold the RFC 0002 decision comparator (DecisionParity) and the offline replay tool. They see cosec-core's
 * internals and are never published: downstream code must not depend on them.
 */
val javaComponent = components["java"] as AdhocComponentWithVariants
listOf("testFixturesApiElements", "testFixturesRuntimeElements").forEach {
    javaComponent.withVariantsFromConfiguration(configurations[it]) { skip() }
}

val mavenLibrary = the<PublishingExtension>().publications.getByName("mavenLibrary") as MavenPublication
val verifyTestFixturesUnpublished = tasks.register("verifyTestFixturesUnpublished") {
    group = "verification"
    description = "Fails if the publication carries the test fixtures: a variant, an artifact or a POM entry."
    val publication = mavenLibrary
    val metadata = tasks.named<GenerateModuleMetadata>("generateMetadataFileForMavenLibraryPublication")
        .flatMap { it.outputFile }
    val pom = tasks.named<GenerateMavenPom>("generatePomFileForMavenLibraryPublication").map { it.destination }
    inputs.file(metadata)
    inputs.file(pom)
    doLast {
        fun mentionsTestFixtures(text: String) = "testFixtures" in text || "test-fixtures" in text
        val metadataText = metadata.get().asFile.readText()
        check(!mentionsTestFixtures(metadataText)) {
            "The published module metadata of ${project.path} offers its test fixtures:\n$metadataText"
        }
        val pomText = pom.get().readText()
        check(!mentionsTestFixtures(pomText)) { "The published POM of ${project.path} names its test fixtures:\n$pomText" }
        val artifacts = publication.artifacts.map { "${it.file.name} (classifier ${it.classifier})" }
        check(artifacts.none(::mentionsTestFixtures)) {
            "The publication of ${project.path} carries test fixture artifacts: $artifacts"
        }
    }
}
tasks.named("check") {
    dependsOn(verifyTestFixturesUnpublished)
}

detekt {
    source.from("src/testFixtures/kotlin")
}

/*
 * RFC 0002 §8.2.1: replays exported real traffic through both authorizations, on the maintainer's machine only.
 * Not part of check. Input format: me.ahoo.cosec.parity.DecisionReplay.
 */
tasks.register<JavaExec>("engineReplay") {
    group = "verification"
    description = "Replays -PreplayDir=<dir> through SimpleAuthorization and EngineAuthorization and reports differences."
    classpath = sourceSets["testFixtures"].runtimeClasspath
    mainClass.set("me.ahoo.cosec.parity.ReplayMainKt")
    // WARN, not the tests' TRACE, and no stack trace per failing sample: the report counts failures.
    jvmArgs("-Dlogback.configurationFile=${rootProject.rootDir}/config/logback-parity.xml")
    val replayDir = providers.gradleProperty("replayDir")
    // ReplayGate thresholds, for trying the tool out; the report records the ones used.
    val gateOptions = mapOf(
        "replayMinSamples" to "--min-samples",
        "replayMinDays" to "--min-days",
        "replayMaxBothFailedRate" to "--max-both-failed-rate",
    ).mapValues { (property, option) -> providers.gradleProperty(property).map { "$option=$it" } }
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(replayDir.orNull ?: throw GradleException("Pass the export directory: -PreplayDir=<dir>.")) +
                gateOptions.values.mapNotNull { it.orNull }
        }
    )
}

/*
 * RFC 0002 §8.2: the random-case parity (EngineParityPropertyTest) with -PparityIterations cases, 1,000,000 by default.
 * Not part of check, which runs it with 2,000.
 */
tasks.register<Test>("engineParity") {
    group = "verification"
    description = "Runs EngineParityPropertyTest with -PparityIterations random cases (default 1,000,000)."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter {
        includeTestsMatching("me.ahoo.cosec.authorization.engine.EngineParityPropertyTest")
    }
    systemProperty("cosec.parity.iterations", providers.gradleProperty("parityIterations").getOrElse("1000000"))
    // The default 2,000-case run is cheap; a million is not, so never skip it as up to date.
    outputs.upToDateWhen { false }
    maxHeapSize = "2g"
    // Replaces the TRACE configuration every Test task gets: at TRACE, and with a stack trace per failing case, a million
    // cases log tens of gigabytes. Set at execution, after every configuration action has run.
    doFirst {
        jvmArgs = jvmArgs.orEmpty().filterNot { it.startsWith("-Dlogback.configurationFile=") } +
            "-Dlogback.configurationFile=${rootProject.rootDir}/config/logback-parity.xml"
    }
    // Nothing worth keeping is captured; the case count is printed to the console instead.
    reports.html.required.set(false)
    reports.junitXml.includeSystemOutLog.set(false)
    reports.junitXml.includeSystemErrLog.set(false)
    testLogging.showStandardStreams = true
}
