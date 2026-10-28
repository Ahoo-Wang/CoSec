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
import io.gitlab.arturbosch.detekt.DetektPlugin
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.dokka.gradle.DokkaPlugin
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.publish)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.dokka)
    alias(libs.plugins.jmh)
    jacoco
}
val dependenciesProject = project(":cosec-dependencies")
val bomProjects = setOf(
    project(":cosec-bom"),
    dependenciesProject,
)

val serverProjects = setOf(
    project(":cosec-gateway-server"),
)
val codeCoverageReportProject = project(":code-coverage-report")
// Test-only modules (RFC 0008 §3.1): never published, so neither in the BOM nor in any publication.
val testOnlyProjects = setOf(
    project(":conformance"),
    project(":e2e"),
)
val publishProjects = subprojects - serverProjects - codeCoverageReportProject - testOnlyProjects
val libraryProjects = publishProjects - bomProjects

// Modules of the decision engine (RFC 0002 §2): they depend on the Kotlin standard library only.
val pureProjects = setOf(
    project(":cosec-model"),
    project(":cosec-engine"),
)

ext.set("libraryProjects", libraryProjects)

allprojects {
    repositories {
        mavenLocal()
        mavenCentral()
    }
    apply<DetektPlugin>()
    configure<DetektExtension> {
        config.setFrom(files("${rootProject.rootDir}/config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
        autoCorrect = true
    }
    dependencies {
        detektPlugins(dependenciesProject)
        detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting")
    }
    tasks.withType<Jar> {
        manifest {
            attributes["Implementation-Title"] = project.name
            attributes["Implementation-Version"] = project.version
        }
    }
}

configure(bomProjects) {
    apply<JavaPlatformPlugin>()
    configure<JavaPlatformExtension> {
        allowDependencies()
    }
}

configure(libraryProjects) {
    apply<DokkaPlugin>()
    apply<JacocoPlugin>()
    apply<JavaLibraryPlugin>()
    configure<JavaPluginExtension> {
        withJavadocJar()
        withSourcesJar()
    }
    kotlinJvm()
    jmhBenchmarks()
    junitTests()

    dependencies {
        if (this@configure in pureProjects) {
            // Keeps the platform out of the published POM; tests and benchmarks still take versions from it.
            testImplementation(platform(dependenciesProject))
        } else {
            api(platform(dependenciesProject))
            implementation("com.google.guava:guava")
            implementation("org.slf4j:slf4j-api")
        }
        jmh(platform(dependenciesProject))
        jmh("org.openjdk.jmh:jmh-core")
        jmh("org.openjdk.jmh:jmh-generator-annprocess")
    }
}

configure(testOnlyProjects) {
    kotlinJvm()
    junitTests()
    dependencies {
        testImplementation(platform(dependenciesProject))
    }
    verifyUnpublished()
}

// The end-to-end slice measures the 8.0 path (RFC 0008 §4.4.3).
project(":e2e") {
    jmhBenchmarks()
    dependencies {
        "jmh"(platform(dependenciesProject))
        "jmh"("org.openjdk.jmh:jmh-core")
        "jmh"("org.openjdk.jmh:jmh-generator-annprocess")
    }
}

configure(pureProjects) {
    // ArchUnit (EnginePurityTest) checks what the code references; this catches a dependency added to the build
    // before any code uses it.
    val allowedModules = setOf("org.jetbrains.kotlin:kotlin-stdlib", "org.jetbrains:annotations")
    val allowedProjects = pureProjects.map { it.path }.toSet()
    val projectPath = path
    val verifyPureClasspath = tasks.register("verifyPureClasspath") {
        group = "verification"
        description = "Fails if a classpath holds anything but the Kotlin standard library and the pure modules."
        val classpaths = listOf("compileClasspath", "runtimeClasspath").associateWith { name ->
            configurations.named(name).flatMap { it.incoming.artifacts.resolvedArtifacts }
        }
        doLast {
            val violations = classpaths.flatMap { (name, artifacts) ->
                artifacts.get().mapNotNull { artifact ->
                    val allowed = when (val id = artifact.id.componentIdentifier) {
                        is ProjectComponentIdentifier -> id.projectPath in allowedProjects
                        is ModuleComponentIdentifier -> "${id.group}:${id.module}" in allowedModules
                        else -> false
                    }
                    if (allowed) null else "$name: ${artifact.id.componentIdentifier.displayName}"
                }
            }
            check(violations.isEmpty()) {
                "Pure module $projectPath must depend on the Kotlin standard library only, found:\n" +
                    violations.joinToString("\n")
            }
        }
    }
    tasks.named("check") {
        dependsOn(verifyPureClasspath)
    }
}

/*
 * RFC 0008 §3.5: the property tests the rule registry (conformance/src/test/resources/rules/<FAMILY>.json) names, with
 * -PpropertyIterations random cases each (default 1,000,000). Not part of check, which runs them with their own fixed
 * counts. A test reads the count from the system property cosec.property.iterations.
 */
val propertyStress = tasks.register("propertyStress") {
    group = "verification"
    description = "Runs the registered property tests with -PpropertyIterations random cases (default 1,000,000)."
}

val ruleRegistry = layout.projectDirectory.dir("conformance/src/test/resources/rules")

@Suppress("UNCHECKED_CAST")
val registeredProperties = ruleRegistry.asFile.listFiles().orEmpty()
    .filter { it.name.endsWith(".json") && it.name != "_meta.json" }
    .sortedBy { it.name }
    .flatMap { family ->
        // Tracked file reads, so the configuration cache is invalidated when the registry changes.
        val text = providers.fileContents(ruleRegistry.file(family.name)).asText.get()
        (groovy.json.JsonSlurper().parseText(text) as Map<String, Any?>)["rules"] as List<Map<String, Any?>>
    }
registeredProperties
    .flatMap { (it["properties"] as List<String>?).orEmpty() }
    // module:Class#test name
    .groupBy({ it.substringBefore(':') }, { it.substringAfter(':').substringBefore('#') })
    .forEach { (directory, classes) ->
        val target = subprojects.single { it.projectDir.name == directory }
        val test = target.the<SourceSetContainer>()["test"]
        val stress = target.tasks.register<Test>("propertyStress") {
            group = "verification"
            description = "Runs the property tests the rule registry names with -PpropertyIterations random cases."
            testClassesDirs = test.output.classesDirs
            classpath = test.runtimeClasspath
            filter {
                classes.toSet().forEach { includeTestsMatching("*.$it") }
            }
            systemProperty(
                "cosec.property.iterations",
                providers.gradleProperty("propertyIterations").getOrElse("1000000"),
            )
            outputs.upToDateWhen { false }
            maxHeapSize = "2g"
            // TRACE logging over a million cases would write gigabytes.
            val parityLogback = "${rootProject.rootDir}/config/logback-parity.xml"
            doFirst {
                jvmArgs = jvmArgs.orEmpty().filterNot { it.startsWith("-Dlogback.configurationFile=") } +
                    "-Dlogback.configurationFile=$parityLogback"
            }
        }
        propertyStress.configure { dependsOn(stress) }
    }

configure(publishProjects) {
    val isBom = bomProjects.contains(this)
    apply<MavenPublishPlugin>()
    apply<SigningPlugin>()
    configure<PublishingExtension> {
        repositories {
            maven {
                name = "projectBuildRepo"
                url = uri(layout.buildDirectory.dir("repos"))
            }
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/Ahoo-Wang/CoSec")
                credentials {
                    username = System.getenv("GITHUB_ACTOR")
                    password = System.getenv("GITHUB_TOKEN")
                }
            }
            maven {
                name = "LinYiPackages"
                url = uri(project.properties["linyiPackageReleaseUrl"].toString())
                credentials {
                    username = project.properties["linyiPackageUsername"]?.toString()
                    password = project.properties["linyiPackagePwd"]?.toString()
                }
            }
        }
        publications {
            val publishName = if (isBom) "mavenBom" else "mavenLibrary"
            val publishComponentName = if (isBom) "javaPlatform" else "java"
            create<MavenPublication>(publishName) {
                from(components[publishComponentName])
                pom {
                    name.set(rootProject.name)
                    description.set(getPropertyOf("description"))
                    url.set(getPropertyOf("website"))
                    issueManagement {
                        system.set("GitHub")
                        url.set(getPropertyOf("issues"))
                    }
                    scm {
                        url.set(getPropertyOf("website"))
                        connection.set(getPropertyOf("vcs"))
                    }
                    licenses {
                        license {
                            name.set(getPropertyOf("license_name"))
                            url.set(getPropertyOf("license_url"))
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("ahoo-wang")
                            name.set("ahoo wang")
                            organization {
                                url.set(getPropertyOf("website"))
                            }
                        }
                    }
                }
            }
        }
    }
    configure<SigningExtension> {
        val isInCI = null != System.getenv("CI")
        if (isInCI) {
            val signingKeyId = System.getenv("SIGNING_KEYID")
            val signingKey = System.getenv("SIGNING_SECRETKEY")
            val signingPassword = System.getenv("SIGNING_PASSWORD")
            useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
        }

        if (isBom) {
            sign(extensions.getByType(PublishingExtension::class).publications["mavenBom"])
        } else {
            sign(extensions.getByType(PublishingExtension::class).publications["mavenLibrary"])
        }
    }
}

nexusPublishing {
    this.repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            username.set(System.getenv("SONATYPE_USERNAME"))
            password.set(System.getenv("SONATYPE_PASSWORD"))
        }
    }
}

fun getPropertyOf(name: String) = project.properties[name]?.toString()

/** Kotlin on Java 17, as every module compiles. */
fun Project.kotlinJvm() {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    configure<KotlinJvmProjectExtension> {
        jvmToolchain(17)
    }
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            freeCompilerArgs = listOf("-Xjsr305=strict", "-Xjvm-default=all-compatibility")
            javaParameters = true
        }
    }
    tasks.withType<JavaCompile> {
        options.compilerArgs.addAll(listOf("-parameters"))
    }
}

/** JUnit 5 with the test libraries every module shares. */
fun Project.junitTests() {
    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging {
            exceptionFormat = TestExceptionFormat.FULL
        }
        // fix logging missing code for JacocoPlugin
        jvmArgs = listOf("-Dlogback.configurationFile=${rootProject.rootDir}/config/logback.xml")
    }
    dependencies {
        testImplementation(platform(rootProject.libs.junit.bom))
        testImplementation("ch.qos.logback:logback-classic")
        testImplementation("me.ahoo.test:fluent-assert-core")
        testImplementation("org.hamcrest:hamcrest")
        testImplementation("io.mockk:mockk") {
            exclude(group = "org.slf4j", module = "slf4j-api")
        }
        testImplementation("org.junit.jupiter:junit-jupiter-api")
        testImplementation("org.junit.jupiter:junit-jupiter-params")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
        testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    }
}

/** The JMH conventions (AGENTS.md "Feedback loop"): `-PjmhIncludes`, `-PjmhFork`, `-PjmhJavaVersion`, ... */
fun Project.jmhBenchmarks() {
    apply<me.champeau.jmh.JMHPlugin>()
    configure<me.champeau.jmh.JmhParameters> {
        val delimiter = ','
        val jmhIncludesKey = "jmhIncludes"
        val jmhExcludesKey = "jmhExcludes"
        val jmhThreadsKey = "jmhThreads"
        val jmhModeKey = "jmhMode"
        val jmhWarmupIterationsKey = "jmhWarmupIterations"
        val jmhIterationsKey = "jmhIterations"
        val jmhForkKey = "jmhFork"
        val jmhProfilersKey = "jmhProfilers"
        val jmhJavaVersionKey = "jmhJavaVersion"

        if (project.hasProperty(jmhIncludesKey)) {
            val jmhIncludes = project.properties[jmhIncludesKey].toString().split(delimiter)
            includes.set(jmhIncludes)
        }
        if (project.hasProperty(jmhExcludesKey)) {
            val jmhExcludes = project.properties[jmhExcludesKey].toString().split(delimiter)
            excludes.set(jmhExcludes)
        }

        warmupIterations.set(project.findProperty(jmhWarmupIterationsKey)?.toString()?.toInt() ?: 1)
        iterations.set(project.findProperty(jmhIterationsKey)?.toString()?.toInt() ?: 1)
        if (project.hasProperty(jmhProfilersKey)) {
            profilers.set(project.properties[jmhProfilersKey].toString().split(delimiter))
        }
        // Benchmarks are compiled for 17 like everything else; this only picks the JDK the forked benchmark JVMs run on.
        if (project.hasProperty(jmhJavaVersionKey)) {
            val launcher = project.the<JavaToolchainService>().launcherFor {
                languageVersion.set(JavaLanguageVersion.of(project.properties[jmhJavaVersionKey].toString()))
            }
            jvm.set(launcher.map { it.executablePath.asFile.absolutePath })
        }
        resultFormat.set("json")

        // Without -PjmhMode, each benchmark keeps its own @BenchmarkMode; unannotated ones run JMH's default, thrpt.
        if (project.hasProperty(jmhModeKey)) {
            benchmarkMode.set(project.properties[jmhModeKey].toString().split(delimiter))
        }
        var jmhThreads = 1
        if (project.hasProperty(jmhThreadsKey)) {
            jmhThreads = Integer.valueOf(project.properties[jmhThreadsKey].toString())
        }
        threads.set(jmhThreads)
        fork.set(project.findProperty(jmhForkKey)?.toString()?.toInt() ?: 1)
        jvmArgs.set(listOf("-Dlogback.configurationFile=${rootProject.rootDir}/config/logback-jmh.xml"))
    }
}

/*
 * RFC 0008 §3.1: a test-only module is in no published artifact. Like cosec-core's verifyTestFixturesUnpublished, it
 * reads what the publications would contain: no publication of this project, and no POM or module metadata naming it.
 */
fun Project.verifyUnpublished() {
    val verifyUnpublished = tasks.register("verifyUnpublished") {
        group = "verification"
        description = "Fails if this module is published, or any published POM or module metadata names it."
        val self = project
        val descriptorTasks = provider {
            rootProject.subprojects.filter { it != self }.flatMap {
                it.tasks.withType<GenerateMavenPom>() + it.tasks.withType<GenerateModuleMetadata>()
            }
        }
        dependsOn(descriptorTasks)
        val descriptors = files(
            descriptorTasks.map { tasks ->
                tasks.map { if (it is GenerateMavenPom) it.destination else (it as GenerateModuleMetadata).outputFile }
            }
        )
        inputs.files(descriptors)
        val ownPublications = self.extensions.findByType<PublishingExtension>()?.publications?.names.orEmpty()
        val hasPublishPlugin = self.pluginManager.hasPlugin("maven-publish")

        @Suppress("UNCHECKED_CAST")
        val inLibraryProjects = (rootProject.extra["libraryProjects"] as Iterable<Project>).any { it == self }
        val name = self.name
        doLast {
            check(!hasPublishPlugin && ownPublications.isEmpty()) { "$name must not apply maven-publish: $ownPublications" }
            check(!inLibraryProjects) { "$name is in the root build's libraryProjects, so the BOM would list it." }
            val naming = descriptors.files.filter { it.isFile && Regex("\\b$name\\b").containsMatchIn(it.readText()) }
            check(naming.isEmpty()) { "Published descriptors name $name:\n${naming.joinToString("\n")}" }
            check(descriptors.files.any { it.isFile }) { "No published descriptor was generated; the check saw nothing." }
        }
    }
    tasks.named("check") {
        dependsOn(verifyUnpublished)
    }
}
