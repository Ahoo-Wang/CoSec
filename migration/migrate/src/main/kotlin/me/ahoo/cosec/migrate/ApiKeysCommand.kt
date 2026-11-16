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

@file:OptIn(InternalCoSecApi::class, ExperimentalCoSecApi::class)

package me.ahoo.cosec.migrate

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.sync.RedisCommands
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.ApiKeyCodec
import me.ahoo.cosec.cocache.CoSecCacheSpecs
import me.ahoo.cosec.cocache.DEFAULT_KEY_PREFIX
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.migrate.ConvertCommand.summarize
import me.ahoo.cosec.migrate.MigrateMain.API_KEYS_REPORT_FILE
import me.ahoo.cosec.migrate.MigrateMain.FAILED
import me.ahoo.cosec.migrate.MigrateMain.OK
import me.ahoo.cosec.migrate.RedisAccess.controlStore
import me.ahoo.cosec.migrate.RedisAccess.scan
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import java.io.IOException
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** `api-keys` (RFC 0009 §4.4). */
internal object ApiKeysCommand {
    private const val STATUS_PLANNED = "planned"
    private val REPLACEABLE_STATUS = setOf(ApiKeyMigrationPlan.STATUS_DRY_RUN, ApiKeyMigrationPlan.STATUS_REJECTED)

    /**
     * `api-keys` (RFC 0009 §4.4): converts the keys under `<--key-prefix>:api-key`, where the deployment reads them
     * (`SNP-9`), to the format of RFC 0009 and writes the
     * change report. With `--dry-run`, or when a key is refused, nothing is written to Redis. Keys already converted are
     * left as they are, so a run can be repeated; each key is rewritten only while it still holds what was read.
     *
     * The report is the only place the roles and policies a key had are kept (the converted key does not hold them), so
     * it is on disk before the first key is rewritten, and a report that holds the plan of a run that rewrote keys is
     * never overwritten: a repeated run, which finds those keys converted, would otherwise replace the plan with an
     * empty one (#1220). The report of a dry run or of a refusal may be replaced by the run that follows it.
     */
    fun apiKeys(options: Options, out: PrintStream): Int {
        val uri = options.required("redis")
        val keyPrefix = options.value("key-prefix") ?: DEFAULT_KEY_PREFIX
        val apiKeyPrefix = CoSecCacheSpecs.apiKeyPrefix(keyPrefix)
        val outDirectory = Path.of(options.required("out"))
        val dryRun = options.flag("dry-run")
        val memberships = options.value("memberships")?.let { MembershipsExport.read(Files.readAllBytes(Path.of(it))) }
        val grantTo = options.value("grant-to-principal")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.toSet().orEmpty()
        val client = RedisClient.create(RedisURI.create(uri))
        try {
            client.connect().use { connection ->
                val commands = connection.sync()
                val stored = StoredApiKeys.read(commands, apiKeyPrefix) { pattern -> scan(commands, pattern) }
                val plan = controlStore(uri, keyPrefix) { store ->
                    ApiKeyMigration(
                        StoredDocuments(store),
                        memberships,
                        grantTo,
                        options.value("role-prefix") ?: ApiKeyMigration.DEFAULT_ROLE_PREFIX,
                        options.value("app"),
                    ).plan(stored.legacy)
                }.withRejections(stored.unreadable)
                val report = outDirectory.resolve(API_KEYS_REPORT_FILE)
                Files.createDirectories(outDirectory)
                requireReplaceable(report)
                val rewrites = plan.accepted && !dryRun
                writeReport(
                    report,
                    plan.report(dryRun, stored.converted, status = if (rewrites) STATUS_PLANNED else null)
                )
                val changed = if (rewrites) write(commands, apiKeyPrefix, plan) else emptyList()
                if (rewrites) {
                    finalReport(report, plan.report(dryRun, stored.converted, changed))
                }
                summarize(plan, out)
                out.println("Wrote $report")
                return outcome(plan, dryRun, changed, out)
            }
        } finally {
            client.shutdown()
        }
    }

    /** The report [report] names may be replaced only while it is the report of a run that rewrote no key. */
    private fun requireReplaceable(report: Path) {
        if (!Files.exists(report, LinkOption.NOFOLLOW_LINKS)) {
            return
        }
        val status = if (Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS)) {
            try {
                CoSecJson.mapper.readTree(Files.readAllBytes(report))?.get("status")?.takeIf { it.isString }?.asString()
            } catch (_: JacksonException) {
                null
            }
        } else {
            null
        }
        require(status in REPLACEABLE_STATUS) {
            "$report exists and is not the report of a dry run or of a refused run: it may hold the plan of keys already " +
                "rewritten, and is never overwritten. Give --out a directory of its own, or move the report away once its plan is carried out."
        }
    }

    /** Writes [json] to [path] as a whole or not at all, and to disk before it returns. */
    private fun writeReport(path: Path, json: JsonNode) {
        val bytes = CoSecJson.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(json)
        val temporary = Files.createTempFile(path.toAbsolutePath().parent, path.fileName.toString(), ".tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                channel.write(ByteBuffer.wrap(bytes))
                channel.force(true)
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /** The report after the keys were rewritten; the planned one stays on disk when this fails. */
    private fun finalReport(report: Path, json: JsonNode) {
        try {
            writeReport(report, json)
        } catch (error: IOException) {
            throw IllegalStateException(
                "The keys were rewritten, but the final $report could not be written (${error.message}): " +
                    "it still holds the plan, with status $STATUS_PLANNED.",
                error,
            )
        }
    }

    /** Rewrites each key over exactly what was read; the hashes of those that changed since, which are left alone. */
    private fun write(
        commands: RedisCommands<String, String>,
        prefix: String,
        plan: ApiKeyMigrationPlan
    ): List<String> =
        plan.keys.filterNot { converted ->
            StoredApiKeys.replace(
                commands,
                "$prefix:${converted.legacy.hash}",
                converted.legacy.json,
                ApiKeyCodec.encode(converted.key)
            )
        }.map { it.legacy.hash }

    private fun outcome(plan: ApiKeyMigrationPlan, dryRun: Boolean, changed: List<String>, out: PrintStream): Int = when {
        !plan.accepted -> {
            out.println("Rejected: fix the ${plan.rejections.size} rejection(s) and run again. No key was written.")
            FAILED
        }

        dryRun -> {
            out.println("Dry run: ${plan.keys.size} key(s) would be converted. No key was written.")
            OK
        }

        changed.isNotEmpty() -> {
            out.println(
                "${changed.size} key(s) changed since they were read and were left as they are: run again. " +
                    "The other ${plan.keys.size - changed.size} were converted."
            )
            FAILED
        }

        else -> {
            out.println(
                "Converted ${plan.keys.size} key(s). Grant the memberships and add the bindings in the report: until " +
                    "then the keys are refused or narrower, never wider."
            )
            OK
        }
    }

    private fun summarize(plan: ApiKeyMigrationPlan, out: PrintStream) {
        plan.rejections.forEach { out.println("REJECT $it") }
        plan.keys.forEach { key ->
            val id = key.key.id
            out.println("KEY    [$id] ${key.mode.name.lowercase().replace('_', '-')}: principal ${key.key.principalId}")
            if (key.legacy.tenantId.isNullOrEmpty()) {
                out.println("WARN   API key [$id] has no tenant: it goes to the default tenant.")
            }
            key.lost.forEach { out.println("WARN   API key [$id] no longer reaches $it.") }
        }
        plan.memberships.forEach { principal ->
            principal.newlyGained?.takeIf { it.isNotEmpty() }?.let {
                out.println("WARN   ${principal.principal}@${principal.tenant.value} newly gains $it.")
            }
        }
    }
}
