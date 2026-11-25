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

// RFC 0014 T: the cross-version fixture. The previous release of CoSec (`cosec-previous` in the catalog) and the current
// build each run in a class loader of their own inside one test JVM, through one bridge, a side, whose source
// (src/side/kotlin) is compiled twice: against the current projects, and against the previous release's published
// artifacts only. The two sides exchange JDK types only (a `BiFunction<String, Map<String, String>, String>`), and read
// and write the same Redis on localhost:6379. Test sources only, never published (verifyUnpublished).

val sideSources = "src/side/kotlin"

/** The previous release, resolved apart: never on a classpath of the current build, and never conflict-resolved with it. */
val cosecPrevious: Configuration = configurations.create("cosecPrevious") {
    isCanBeConsumed = false
    isCanBeResolved = true
    description = "The previous release of CoSec and its own dependencies, as published"
}

val sourceSets = the<SourceSetContainer>()

/** The side over the current build. */
val current: SourceSet = sourceSets.create("current") {
    kotlin.srcDirs(sideSources, "src/current/kotlin")
}

/** The same side over the previous release; src/previous/kotlin holds what differs from the current one, if anything. */
val previous: SourceSet = sourceSets.create("previous") {
    kotlin.srcDirs(sideSources, "src/previous/kotlin")
    compileClasspath = cosecPrevious
    runtimeClasspath = output + cosecPrevious
}

dependencies {
    cosecPrevious(libs.cosec.previous.cocache)
    cosecPrevious(libs.cosec.previous.control)
    cosecPrevious(libs.cosec.previous.jwt)

    "currentImplementation"(platform(project(":dependencies")))
    "currentImplementation"(project(":cocache"))
    "currentImplementation"(project(":control"))
    "currentImplementation"(project(":jwt"))
    "currentImplementation"("org.springframework.data:spring-data-redis")
    "currentImplementation"("io.lettuce:lettuce-core")
    "currentImplementation"("me.ahoo.cocache:cocache-redis")

    // The tests see the sides through the JDK only, and Redis through Lettuce to look at what a side left there.
    testImplementation("io.lettuce:lettuce-core")
}

/** Each side's classpath, handed to the test JVM: an input of the test task, so a change to either reruns it. */
abstract class SideClasspaths : CommandLineArgumentProvider {
    @get:Classpath
    abstract val current: ConfigurableFileCollection

    @get:Classpath
    abstract val previous: ConfigurableFileCollection

    override fun asArguments(): Iterable<String> = listOf(
        "-Dcosec.compat.current=${current.files.joinToString(File.pathSeparator)}",
        "-Dcosec.compat.previous=${previous.files.joinToString(File.pathSeparator)}",
    )
}

tasks.named<Test>("test") {
    jvmArgumentProviders.add(
        objects.newInstance<SideClasspaths>().apply {
            current.from(sourceSets["current"].runtimeClasspath)
            previous.from(sourceSets["previous"].runtimeClasspath)
        }
    )
    // What the previous side is, for the cases that hold only from a release on (Baseline).
    systemProperty("cosec.compat.previous.version", libs.versions.cosec.previous.get())
    // Netty's native transports load a JNI library once per JVM: each side runs on NIO.
    systemProperty("io.netty.transport.noNative", "true")
}
