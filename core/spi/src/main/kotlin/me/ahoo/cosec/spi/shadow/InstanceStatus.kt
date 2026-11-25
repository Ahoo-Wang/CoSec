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

@file:OptIn(InternalCoSecApi::class)

package me.ahoo.cosec.spi.shadow

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import java.time.Instant

/**
 * An instance's status at [at] (RFC 0007 §6.5): that it is live, whether it shadows, and the windows it acknowledges.
 * Small, and written every report interval: the documents it holds are its [ResidentDocuments], a record of their own
 * that only `propagation` reads (`CTL-74`).
 */
@ExperimentalCoSecApi
class InstanceStatus(
    val instance: String,
    val at: Instant,
    /**
     * The document format level this instance reads (RFC 0010 C5): 8.0 is [FORMAT_LEVEL], and the status codec requires
     * it. The publish gate that compares it with a document's level arrives with the first document field that needs
     * one (8.1); until then it is carried and not read.
     */
    val formatLevel: Int = FORMAT_LEVEL,
    /**
     * The shadow windows of the staged list this instance holds and has compiled the candidate of, or reported it as
     * failing to (design D2 §5, `CTL-10`): its acknowledgement that it compares their candidates. A window closes as
     * passed only when every instance with a recent status that [shadows] lists it. Null when the instance does not send
     * it: a version before it, or an instance that holds no staged list; such an instance acknowledges nothing.
     */
    val windows: Set<String>? = null,
    /**
     * Whether this instance takes part in shadowing: it compiles the staged candidates through CoSec's own candidate
     * source, so it can acknowledge their windows. False for an instance whose candidate source is an application's
     * own; the closing rule does not wait for it, and `propagation` still counts it. A version before this one has no
     * such member and reads as true: it shadows, and acknowledges nothing.
     */
    val shadows: Boolean = true,
    /**
     * The CoSec release this instance runs (`8.0.0-beta.9`, RFC 0014 R5, `SNP-25`): what a later release reads to know
     * what this one understands, without this one telling it anything else. Null when a status does not say, as one of
     * a release before it does not: an unknown release, which nothing in 8.0 counts or waits on. Null unless given: a
     * status rebuilt from what an instance wrote names that instance's release, never the reading process's; the
     * instance's own reporter passes [RELEASE].
     */
    val release: String? = null,
) {
    private fun fields(): List<Any?> = listOf(instance, at, formatLevel, windows, shadows, release)

    override fun equals(other: Any?): Boolean = other is InstanceStatus && other.fields() == fields()

    override fun hashCode(): Int = fields().hashCode()

    override fun toString(): String = "InstanceStatus(${fields().joinToString()})"

    companion object {
        /**
         * The document format level of this version: 8.0 reads level 1. Not a `const`, so that code compiled against one
         * release reads the level of the release it runs on.
         */
        @JvmField
        @Suppress("MayBeConst") // on purpose: a const is inlined into the caller's class file
        val FORMAT_LEVEL: Int = 1

        /** Where the build writes the release ([RELEASE]): `cosec-spi`'s own resource, so a repackaged jar keeps it. */
        const val RELEASE_RESOURCE: String = "META-INF/cosec/release"

        /**
         * The CoSec release of this process, from [RELEASE_RESOURCE]; null when the resource is not on the class path,
         * which a status then reports as an unknown release.
         */
        @JvmField
        val RELEASE: String? = InstanceStatus::class.java.classLoader?.getResource(RELEASE_RESOURCE)
            ?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    }
}
