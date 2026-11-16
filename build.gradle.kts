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
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    alias(libs.plugins.publish)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.dokka)
    alias(libs.plugins.jmh)
    jacoco
}
// The versions CoSec itself is built and tested with. An internal platform: it is in no publication, and no published
// module names it (a library publishes the versions it resolved, see `versionMapping` below).
val dependenciesProject = project(":dependencies")
val bomProject = project(":bom")
val bomProjects = setOf(bomProject, dependenciesProject)

val serverProjects = setOf(
    project(":gateway-server"),
)
val codeCoverageReportProject = project(":code-coverage-report")
// Test-only modules (RFC 0008 §3.1): never published, so neither in the BOM nor in any publication.
val testOnlyProjects = setOf(
    project(":conformance"),
    project(":architecture"),
    project(":e2e"),
)
val publishProjects = subprojects - serverProjects - codeCoverageReportProject - testOnlyProjects - dependenciesProject
val libraryProjects = publishProjects - bomProject

// The contracts and the decision engine (RFC 0002 §2, RFC 0011 §5): the Kotlin standard library only.
val pureProjects = setOf(
    project(":annotations"),
    project(":api"),
    project(":model"),
    project(":engine"),
)

// The ports (RFC 0011 §3): what a third party implements, so no logging facade forced on it.
val spiProject = project(":spi")

// The documents and their formats (RFC 0013 §3): they log nothing, so neither does what only reads a document.
val documentProject = project(":document")

// The modules whose public ABI is kept in a dump (RFC 0011 D4). cosec-migrate is a command-line tool: its contract is
// the command line, and its classes are not a library API.
val abiProjects = libraryProjects - project(":migrate")

/** RFC 0011 D1: a project is named after its directory, and its artifact keeps the cosec- prefix. */
fun artifactIdOf(project: Project): String = "cosec-${project.name}"

ext.set("libraryProjects", libraryProjects)

allprojects {
    repositories {
        // A jar in ~/.m2 with the coordinates of a published one shadows it, and CI has none: only when asked, with
        // -Pcosec.mavenLocal (or in ~/.gradle/gradle.properties), to test against a locally published CoCache or CosId.
        if (providers.gradleProperty("cosec.mavenLocal").isPresent) {
            mavenLocal()
        }
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
    configure<BasePluginExtension> {
        archivesName.set(artifactIdOf(project))
    }
    kotlinJvm()
    if (this@configure in abiProjects) {
        abiValidation()
    }
    jmhBenchmarks()
    junitTests()
    coverageReport()

    // The module compiles and tests against the versions of `cosec-dependencies`, through a configuration that no
    // published variant extends: the platform is not in the module's POM or Gradle module metadata, which name the
    // versions it resolved instead (`versionMapping`, below). The pure projects keep it off their main classpaths.
    val buildPlatform = configurations.create("buildPlatform") {
        isCanBeConsumed = false
        isCanBeResolved = false
    }
    val platformClasspaths = setOf("testCompileClasspath", "testRuntimeClasspath") +
        if (this@configure in pureProjects) emptySet() else setOf("compileClasspath", "runtimeClasspath", "architectureApiConsumer")
    configurations.matching { it.name in platformClasspaths }.configureEach { extendsFrom(buildPlatform) }

    dependencies {
        buildPlatform(platform(dependenciesProject))
        if (this@configure !in pureProjects && this@configure != spiProject && this@configure != documentProject) {
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

// RFC 0011 §5: what each project declares in its main configurations and what its classpaths resolve to, written for
// test/architecture, which holds the dependency rules as data. A project's own task resolves its own configurations.
subprojects {
    pluginManager.withPlugin("java") {
        val declared = provider {
            listOf("api", "implementation", "compileOnly", "compileOnlyApi", "runtimeOnly").flatMap { name ->
                configurations.findByName(name)?.dependencies.orEmpty().map { dependency ->
                    when (dependency) {
                        is ProjectDependency -> "declared $name project ${dependency.path.removePrefix(":")}"
                        else -> "declared $name module ${dependency.group}:${dependency.name}"
                    }
                }
            }
        }
        // What an application that compiles against the project, reading its Gradle API variant (usage java-api), gets
        // from it: the project and what its `api` dependencies name. Not the implementation classpath the project's
        // own tests see, which hides a dependency a public signature needs and the API variant does not export.
        val apiConsumer = configurations.create("architectureApiConsumer") {
            isCanBeConsumed = false
            isCanBeResolved = true
            attributes {
                attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
                attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
                attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
            }
        }
        dependencies.add(apiConsumer.name, dependencies.project(mapOf("path" to path)))
        val resolved = listOf(
            "compileClasspath" to "compileClasspath",
            "runtimeClasspath" to "runtimeClasspath",
            apiConsumer.name to "apiElements",
        ).map { (configuration, name) ->
            configurations.named(configuration).flatMap { it.incoming.artifacts.resolvedArtifacts }.map { artifacts ->
                artifacts.map { artifact ->
                    when (val id = artifact.id.componentIdentifier) {
                        is ProjectComponentIdentifier -> "resolved $name project ${id.projectPath.removePrefix(":")}"
                        is ModuleComponentIdentifier -> "resolved $name module ${id.group}:${id.module}"
                        else -> "resolved $name other ${id.displayName}"
                    }
                }
            }
        }
        val facts = layout.buildDirectory.file("architecture/facts.txt")
        tasks.register("architectureFacts") {
            group = "verification"
            description = "Writes the project's declared and resolved dependencies for test/architecture."
            inputs.property("declared", declared)
            resolved.forEachIndexed { index, lines -> inputs.property("resolved$index", lines) }
            outputs.file(facts)
            doLast {
                val lines = declared.get() + resolved.flatMap { it.get() }
                facts.get().asFile.apply { parentFile.mkdirs() }.writeText(lines.distinct().joinToString("\n"))
            }
        }
    }
}

/*
 * RFC 0008 §3.5: the property tests the rule registry (test/conformance/src/test/resources/rules/<FAMILY>.json) names, with
 * -PpropertyIterations random cases each (default 1,000,000). Not part of check, which runs them with their own fixed
 * counts. A test reads the count from the system property cosec.property.iterations.
 */
val propertyStress = tasks.register("propertyStress") {
    group = "verification"
    description = "Runs the registered property tests with -PpropertyIterations random cases (default 1,000,000)."
}

val ruleRegistry = project(":conformance").layout.projectDirectory.dir("src/test/resources/rules")

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
    // project:Class#test name
    .groupBy({ it.substringBefore(':') }, { it.substringAfter(':').substringBefore('#') })
    .forEach { (name, classes) ->
        val target = project(":$name")
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
            val propertyStressLogback = "${rootProject.rootDir}/config/logback-property-stress.xml"
            doFirst {
                jvmArgs = jvmArgs.orEmpty().filterNot { it.startsWith("-Dlogback.configurationFile=") } +
                    "-Dlogback.configurationFile=$propertyStressLogback"
            }
        }
        propertyStress.configure { dependsOn(stress) }
    }

configure(publishProjects) {
    val isBom = this == bomProject
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
                artifactId = artifactIdOf(project)
                if (!isBom) {
                    // Versionless dependencies are published with the versions they resolved to, as a requirement
                    // (a minimum, so an application on a newer Spring Boot keeps it), never as an imported platform.
                    versionMapping {
                        usage("java-api") { fromResolutionOf("runtimeClasspath") }
                        usage("java-runtime") { fromResolutionResult() }
                    }
                }
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
    // compileOnlyApi is in the API variant only, so Gradle's own metadata is right; the POM maps that variant to compile
    // scope, which Maven carries to run time. `provided` is what the build means: needed to compile against this module's
    // classes, supplied by the application, never brought along. Read after the module's script has declared them.
    afterEvaluate {
        if (!isBom) {
            val coordinates = publishProjects.associate { it.path to "${it.group}:${artifactIdOf(it)}" }
            val compileOnlyApi = configurations.getByName("compileOnlyApi").dependencies.map {
                if (it is ProjectDependency) coordinates.getValue(it.path) else "${it.group}:${it.name}"
            }
            configure<PublishingExtension> {
                publications.withType<MavenPublication>().configureEach {
                    pom.withXml {
                        val pomElement = asElement()
                        val dependencyLists = (0 until pomElement.childNodes.length).map { pomElement.childNodes.item(it) }
                            .filterIsInstance<org.w3c.dom.Element>().filter { it.tagName == "dependencies" }
                        dependencyLists.flatMap { list ->
                            (0 until list.childNodes.length).map { list.childNodes.item(it) }
                                .filterIsInstance<org.w3c.dom.Element>()
                        }.forEach { dependency ->
                            val group = dependency.getElementsByTagName("groupId").item(0)?.textContent
                            val artifact = dependency.getElementsByTagName("artifactId").item(0)?.textContent
                            if ("$group:$artifact" in compileOnlyApi) {
                                dependency.getElementsByTagName("scope").item(0)?.textContent = "provided"
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

/** Kotlin on Java 21, as every module compiles: the toolchain, and with it Kotlin's and Java's target. */
fun Project.kotlinJvm() {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    configure<KotlinJvmProjectExtension> {
        jvmToolchain(21)
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

/**
 * RFC 0011 D4: `check` runs `checkKotlinAbi`, which fails when the module's public ABI differs from its dump,
 * `api/<project>.api`; `updateKotlinAbi` rewrites the dump. `@InternalCoSecApi` declarations are left out of it.
 */
@OptIn(ExperimentalAbiValidation::class)
fun Project.abiValidation() {
    configure<KotlinJvmProjectExtension> {
        abiValidation {
            filters {
                exclude {
                    annotatedWith.add("me.ahoo.cosec.annotations.InternalCoSecApi")
                }
            }
        }
    }
}

/** JUnit 5 with the test libraries every module shares. */
fun Project.junitTests() {
    val onCi = providers.environmentVariable("CI").isPresent
    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging {
            exceptionFormat = TestExceptionFormat.FULL
        }
        // fix logging missing code for JacocoPlugin
        jvmArgs = listOf("-Dlogback.configurationFile=${rootProject.rootDir}/config/logback.xml")
        // A property test's failure names its seed; -PpropertySeed=<seed> runs that seed alone.
        providers.gradleProperty("propertySeed").orNull?.let { systemProperty("cosec.property.seed", it) }
        // A cache hit would pass CI on another build's results, and give Codecov another build's coverage.
        outputs.doNotCacheIf("CI runs every test") { onCi }
    }
    dependencies {
        testImplementation(platform(rootProject.libs.junit.bom))
        testImplementation("ch.qos.logback:logback-classic")
        testImplementation("me.ahoo.test:fluent-assert-core")
        testImplementation("io.mockk:mockk") {
            exclude(group = "org.slf4j", module = "slf4j-api")
        }
        testImplementation("org.junit.jupiter:junit-jupiter-api")
        testImplementation("org.junit.jupiter:junit-jupiter-params")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
        testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    }
}

/**
 * `check` writes the module's JaCoCo XML, which CI uploads to Codecov (scripts/ci/plan.py). It holds the module's tests
 * against its own classes and those of the projects on its test runtime classpath, as the aggregated report does, so
 * Codecov's merge of the uploads gives the same coverage.
 */
fun Project.coverageReport() {
    val selfPath = path
    val report = tasks.named<JacocoReport>("jacocoTestReport") {
        reports.xml.required.set(true)
        classDirectories.from(
            configurations["testRuntimeClasspath"].incoming.artifactView {
                componentFilter { it is ProjectComponentIdentifier && it.projectPath != selfPath }
            }.files
        )
    }
    tasks.named("check") {
        dependsOn(report)
    }
}

/** The JMH conventions (AGENTS.md "Feedback loop"): `-PjmhIncludes`, `-PjmhFork`, `-PjmhJavaVersion`, ... */
fun Project.jmhBenchmarks() {
    apply<me.champeau.jmh.JMHPlugin>()
    // A contract change must not leave benchmarks that no longer compile: `check` is the only gate that runs everywhere.
    tasks.named("check") { dependsOn("compileJmhKotlin") }
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
        // Benchmarks are compiled for 21 like everything else; this only picks the JDK the forked benchmark JVMs run on.
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
