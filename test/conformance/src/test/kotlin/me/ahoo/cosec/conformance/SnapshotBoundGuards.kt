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

package me.ahoo.cosec.conformance

/**
 * The `build` guards of `SNP-24` (the snapshot families' bounds derived from the documents' share of the heap), apart
 * from [GuardAllowlist] so that it stays one reviewable list within detekt's size for a class: listed here, they are
 * allowed exactly as there.
 */
internal object SnapshotBoundGuards {
    val BUILD: Set<String> = setOf(
        "spring-boot:DerivedSnapshotSettingsTest#the snapshot sources of a process share the documents' quarter of the heap",
        "spring-boot:UnknownPropertiesTest#a removed key is said to be no longer a property, with what replaced it",
        "runtime:SnapshotDerivedBoundsTest#the budget's parts at the production overlay's heap and a small pod's",
        "runtime:SnapshotDerivedBoundsTest#the bound follows what is resident, and housekeeping takes a family back to it",
        "runtime:SnapshotDerivedBoundsTest#ids nobody owns never shrink what the documents get",
        "runtime:SnapshotDerivedBoundsTest#a document heavier than the whole share leaves its family at the floor, and is served",
        "runtime:SnapshotDerivedBoundsTest#one very large tenant lowers the others' bound by its own weight, no more",
        "runtime:SnapshotDerivedBoundsTest#a small pod's budget turns ready",
        "runtime:DocumentWeightTest#the estimate is above what a resident document retains, and at most 1·6 times it",
        "runtime:DocumentReadinessSmallBoundTest#a host whose bound is smaller than its prewarm set turns ready, and holds no more than its cap",
        "spring-boot:DerivedHeapTest#the startup line names each bound, what it came to and from what",
    )
}
