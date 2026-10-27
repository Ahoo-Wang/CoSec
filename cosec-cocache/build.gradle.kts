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
    api(project(":cosec-core"))
    api("me.ahoo.cocache:cocache-core")
    api("me.ahoo.cocache:cocache-codec-jackson")
    api("me.ahoo.cocache:cocache-redis")
    api("org.springframework.data:spring-data-redis")
    // The non-blocking reads behind the suspend SPIs of RFC 0006 (RevocationReader, MembershipProvider) await
    // CoCache's async view.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    // CoCacheControlStore implements the control plane's ControlStore (RFC 0007 §5.1). Compile-only, so an application
    // that reads snapshots without the control plane does not pull it in; the control plane brings it with it.
    compileOnly(project(":cosec-control"))
    // ReactiveRedisRateLimiter implements the pipeline's suspending RateLimiter (RFC 0006 §8.4). Compile-only, as above:
    // an application running the coroutine pipeline brings cosec-runtime with it.
    compileOnly(project(":cosec-runtime"))
    testImplementation(project(":cosec-control"))
    testImplementation("me.ahoo.cocache:cocache-test")
    // MembershipCacheFailureTest resolves through the pipeline's MembershipResolver.
    testImplementation(project(":cosec-runtime"))
    testImplementation("io.lettuce:lettuce-core")
    // The phase-3 snapshot tools read 7.x data through cosec-migrate's reader (removed at switch S2).
    testImplementation(project(":cosec-migrate"))
    testImplementation("me.ahoo.cosid:cosid-core")
    testImplementation("me.ahoo.cosid:cosid-test")
    testImplementation("io.projectreactor:reactor-test")
    // P5 against the real repositories: the generator and the PolicySet comparison (RFC 0003 §8.4).
    testImplementation(testFixtures(project(":cosec-core")))
    // RFC 0003 §6: the snapshot store must not block a non-blocking caller.
    testImplementation("io.projectreactor.tools:blockhound")
    // cosec-core matches paths with Spring's PathPattern, which it leaves to the application.
    jmh("org.springframework:spring-web")
    // The snapshot side assembles cosec-core's internal snapshot authorization (RFC 0003 PR-5 wires it).
    jmh(testFixtures(project(":cosec-core")))
}

tasks.withType<Test>().configureEach {
    // BlockHound instruments JDK methods, which JDK 13+ allows only with this flag.
    jvmArgs("-XX:+AllowRedefinitionToAddDeleteMethods")
    // Opt-in Redis targets of the snapshot tests (RedisTarget): a change reruns the tests instead of reusing results.
    listOf("COSEC_TEST_VALKEY_PORT", "COSEC_TEST_REDIS_CLUSTER_NODES").forEach { name ->
        inputs.property(name, providers.environmentVariable(name).orElse(""))
    }
}

/*
 * RFC 0003 §4.7: converts the 7.x policy data under -PkeyPrefix in -PredisUrl into snapshot documents. For development
 * deployments, the gate and sizing; lives in the test source set and is never published. Not part of check.
 */
tasks.register<JavaExec>("snapshotConvert") {
    group = "application"
    description = "Converts the 7.x policy data in -PredisUrl (-PkeyPrefix, -PdryRun, -Pinstances, -PrevalidateInterval)."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("me.ahoo.cosec.cache.snapshot.SnapshotConvert")
    listOf("redisUrl", "keyPrefix", "dryRun", "instances", "revalidateInterval").forEach { name ->
        providers.gradleProperty(name).orNull?.let { systemProperty("cosec.snapshot.convert.$name", it) }
    }
}

/*
 * RFC 0003 §4.8: sizes the snapshot documents of the 7.x data under -PkeyPrefix in -PredisUrl. Reads only, unless
 * -ProundTrips=true. On the maintainer's machine; the output stays there. Not part of check.
 */
tasks.register<JavaExec>("snapshotMeasure") {
    group = "verification"
    description = "Measures the snapshot documents of -PredisUrl (-PkeyPrefix, -PrequestsFile, -Pinstances, " +
        "-PrevalidateInterval, -ProundTrips, -Poutput)."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("me.ahoo.cosec.cache.snapshot.SnapshotMeasure")
    jvmArgs("-Dlogback.configurationFile=${rootProject.rootDir}/config/logback-parity.xml")
    listOf("redisUrl", "keyPrefix", "requestsFile", "instances", "revalidateInterval", "roundTrips", "output")
        .forEach { name ->
            providers.gradleProperty(name).orNull?.let { systemProperty("cosec.snapshot.measure.$name", it) }
        }
}

/*
 * RFC 0003 §8.2: replays an export (RFC 0002 §8.2.1) with the snapshot read path as the candidate, the export reaching
 * it through the 7.x reader, the publisher and the snapshot store, all in memory. On the maintainer's machine only;
 * not part of check.
 */
tasks.register<JavaExec>("snapshotReplay") {
    group = "verification"
    description = "Replays -PreplayDir=<dir> through the engine over repositories and over policy snapshots."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("me.ahoo.cosec.cache.snapshot.SnapshotReplay")
    // WARN, not the tests' TRACE, and no stack trace per failing sample: the report counts failures.
    jvmArgs("-Dlogback.configurationFile=${rootProject.rootDir}/config/logback-parity.xml")
    val replayDir = providers.gradleProperty("replayDir")
    // The same ReplayGate thresholds as :cosec-core:engineReplay; the report records the ones used.
    val gateOptions = mapOf(
        "replayMinSamples" to "--min-samples",
        "replayMinDays" to "--min-days",
        "replayMinDistinctDays" to "--min-distinct-days",
        "replayMaxBothFailedRate" to "--max-both-failed-rate",
        "replayMinAgreementRate" to "--min-agreement-rate",
    ).mapValues { (property, option) -> providers.gradleProperty(property).map { "$option=$it" } }
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(replayDir.orNull ?: throw GradleException("Pass the export directory: -PreplayDir=<dir>.")) +
                gateOptions.values.mapNotNull { it.orNull }
        }
    )
}

/*
 * RFC 0003 §8.2: the random-case decision parity through the 7.x reader, the publisher and the snapshot store
 * (SnapshotDecisionParityPropertyTest) with -PparityIterations cases, 1,000,000 by default, on -PparityThreads threads.
 * Not part of check, which runs it with 2,000 on one thread.
 */
tasks.register<Test>("snapshotParity") {
    group = "verification"
    description = "Runs SnapshotDecisionParityPropertyTest with -PparityIterations random cases (default 1,000,000)."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter {
        includeTestsMatching("me.ahoo.cosec.cache.snapshot.SnapshotDecisionParityPropertyTest")
    }
    systemProperty("cosec.parity.iterations", providers.gradleProperty("parityIterations").getOrElse("1000000"))
    systemProperty("cosec.parity.threads", providers.gradleProperty("parityThreads").getOrElse("4"))
    // The default 2,000-case run is cheap; a million is not, so never skip it as up to date.
    outputs.upToDateWhen { false }
    maxHeapSize = "2g"
    // As :cosec-core:engineParity: at TRACE, a million cases log tens of gigabytes.
    doFirst {
        jvmArgs = jvmArgs.orEmpty().filterNot { it.startsWith("-Dlogback.configurationFile=") } +
            "-Dlogback.configurationFile=${rootProject.rootDir}/config/logback-parity.xml"
    }
    reports.html.required.set(false)
    reports.junitXml.includeSystemOutLog.set(false)
    reports.junitXml.includeSystemErrLog.set(false)
    testLogging.showStandardStreams = true
}
