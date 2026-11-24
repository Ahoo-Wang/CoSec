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

/** The build guards of the stored records' reader's rule (SNP-27 to SNP-29, OBS-12, IDN-31): kept apart for size. */
internal object StoredRecordGuards {
    private const val CRIT = "document:StoredCritTest#"
    private const val MEMBERS = "cocache:TokenFamilyMembersTest#"
    private const val LATER = "login:LaterSessionRecordTest#"
    private const val RECORDS = "cocache:StoredRecordMembersTest#"

    val BUILD: Set<String> = setOf(
        // SNP-27: a member crit names that a reader does not know refuses that one record, never read or rewritten.
        "${CRIT}a record without crit, or with an empty one, reads as before",
        "${CRIT}a member crit names that this version knows is read, through an array's elements too",
        "${CRIT}a member crit names that this version does not know refuses the record as a later version's, and counts it",
        "${CRIT}a later format is a later version's too, and counts as one",
        "${CRIT}a crit that is not one, or a record that is not one, is malformed, and counts as malformed",
        "${CRIT}a path is printed printably, and a member is listed once",
        "${MEMBERS}a later member crit names is refused by an older reader, which never rewrites it",
        "${MEMBERS}a later member without crit is dropped by an older reader, and the session it leaves is the same session",
        "${MEMBERS}every member an 8_0 reader knows survives its rewrite",
        "${LATER}a refresh of a session a later version wrote is an outage, and the record is never rewritten",
        "${LATER}the log says a later version wrote it, once per interval at WARN, and a broken store is not told so",
        "${RECORDS}a later member crit names refuses that record as a later version's, which is never rewritten",
        "${RECORDS}a later member without crit is dropped by a rewrite, which leaves the same record",
        "control:LaterRecordsTest#a record a later version wrote is a 503 without detail, told as that",
        "control:LaterRecordsTest#a later record is told at WARN once per interval, and a broken store is not told as one",
        // SNP-28: every member of a kind of record registered, with its absence and its two ways to widen, apart.
        "${MEMBERS}the registry names every member the codec writes, and only those",
        "${MEMBERS}each absent member reads as the registry says",
        "${MEMBERS}whether ignoring a member widens, and whether dropping it does, are each stated",
        "${RECORDS}every kind of record has a registry, and each registry names every member its codec writes, and only those",
        "${RECORDS}each absent member reads as the registry says",
        "${RECORDS}whether ignoring a member widens, and whether dropping it does, are each stated",
        // SNP-29: format numbers frozen within 8.x.
        "${MEMBERS}the session's format numbers are frozen for 8_x",
        "${RECORDS}the records' format numbers are frozen for 8_x",
        // OBS-12: the refusals counted by record and reason.
        "micrometer:StoredRefusedMetricTest#each series reads the count of its record and reason, and the series are the closed sets only",
    )
}
