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

package me.ahoo.cosec.migrate

import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import io.lettuce.core.api.sync.RedisCommands
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.CoSec
import me.ahoo.cosec.cache.ApiKeyCodec
import me.ahoo.cosec.cache.CoSecCacheSpecs
import me.ahoo.cosec.cache.control.CoCacheControlStore
import me.ahoo.cosec.cache.snapshot.createPolicySnapshotCache
import me.ahoo.cosec.migrate.v7.api.principal.RootPrincipal
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.runtime.json.CoSecJson
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.system.exitProcess

/**
 * `cosec-migrate`: converts CoSec 7.x policy data into 8.0 documents and publishes them (RFC 0004 §8.2).
 *
 * ```
 * convert --from-redis redis://localhost:6379 [--key-prefix cosec] --out ./converted
 *         [--partition platform|by-owner] [--root-enabled] [--root-id cosec] [--root-expires-at 2026-12-31T00:00:00Z]
 * convert --from-files ./export --out ./converted [...]
 *         [--rate-limit-rules ./rate-limit.json]
 * publish --to-redis redis://localhost:6379 [--key-prefix cosec] --in ./converted [--resume] [--break-glass-max-duration PT4H]
 * bootstrap --to-redis redis://localhost:6379 [--key-prefix cosec] --platform-admin alice [--force]
 * partition plan (--from-redis <uri> [--key-prefix cosec] | --from-files ./export) --out ./plan
 * api-keys --redis redis://localhost:6379 [--api-key-prefix cosec:api-key] [--key-prefix cosec] --out ./api-keys
 *          [--memberships ./memberships.json] [--grant-to-principal <ids>|all] [--role-prefix api-key.] [--dry-run]
 * ```
 *
 * `publish` and `bootstrap` write through the control plane's store, so each document's revision chain starts with
 * what they write (RFC 0007 §5.9).
 *
 * Exit codes: 0 done; 1 the data was rejected or publishing failed (see `report.json` and the console); 2 usage.
 */
object MigrateMain {
    const val OK = 0
    const val FAILED = 1
    const val USAGE = 2
    private const val SCAN_COUNT = 1000L
    private const val DEFAULT_API_KEY_PREFIX = "cosec:api-key"
    const val API_KEYS_REPORT_FILE = "api-keys-report.json"

    private val USAGE_TEXT = """
        Usage:
          convert (--from-redis <uri> [--key-prefix <p>] | --from-files <dir>) --out <dir>
                  [--partition platform|by-owner] [--root-enabled] [--root-id <id>] [--root-expires-at <instant>]
                  [--rate-limit-rules <file>]
          publish --to-redis <uri> [--key-prefix <p>] --in <dir> [--resume] [--break-glass-max-duration <duration>]
          bootstrap --to-redis <uri> [--key-prefix <p>] --platform-admin <principal> [--force]
          partition plan (--from-redis <uri> [--key-prefix <p>] | --from-files <dir>) --out <dir>
          api-keys --redis <uri> [--api-key-prefix <p>] [--key-prefix <p>] --out <dir> [--memberships <file>]
                   [--grant-to-principal <ids>|all] [--role-prefix <p>] [--dry-run]
    """.trimIndent()

    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(run(args.toList(), System.out))
    }

    @Suppress("TooGenericExceptionCaught")
    fun run(args: List<String>, out: PrintStream): Int {
        val command = args.firstOrNull().let { if (it == "partition" && args.getOrNull(1) == "plan") "partition plan" else it }
        val options = try {
            Options.parse(args.drop(if (command == "partition plan") 2 else 1))
        } catch (error: IllegalArgumentException) {
            out.println(error.message)
            out.println(USAGE_TEXT)
            return USAGE
        }
        return try {
            when (command) {
                "convert" -> convert(options, out)
                "publish" -> publish(options, out)
                "bootstrap" -> bootstrap(options, out)
                "partition plan" -> partitionPlan(options, out)
                "api-keys" -> apiKeys(options, out)
                else -> {
                    out.println(USAGE_TEXT)
                    USAGE
                }
            }
        } catch (error: UsageException) {
            out.println(error.message)
            out.println(USAGE_TEXT)
            USAGE
        } catch (error: Exception) {
            out.println("Failed: ${error.message}")
            FAILED
        }
    }

    private class UsageException(message: String) : IllegalArgumentException(message)

    /** `--name value` pairs and `--flag`s; a repeated or unknown option is a usage error. */
    private class Options(private val values: Map<String, String>, private val flags: Set<String>) {
        fun value(name: String): String? = values[name]

        fun required(name: String): String = values[name] ?: throw UsageException("Missing --$name.")

        fun flag(name: String): Boolean = name in flags

        companion object {
            private val VALUED = setOf(
                "from-redis", "from-files", "key-prefix", "out", "partition", "root-id", "root-expires-at", "to-redis", "in",
                "rate-limit-rules", "platform-admin", "break-glass-max-duration", "redis", "api-key-prefix", "role-prefix",
                "memberships", "grant-to-principal",
            )
            private val FLAGS = setOf("root-enabled", "resume", "force", "dry-run")

            fun parse(args: List<String>): Options {
                val values = HashMap<String, String>()
                val flags = HashSet<String>()
                var i = 0
                while (i < args.size) {
                    val name = args[i].removePrefix("--")
                    require(args[i].startsWith("--")) { "Unexpected argument [${args[i]}]." }
                    when (name) {
                        in FLAGS -> require(flags.add(name)) { "--$name is given twice." }
                        in VALUED -> {
                            require(i + 1 < args.size) { "--$name needs a value." }
                            require(values.put(name, args[i + 1]) == null) { "--$name is given twice." }
                            i++
                        }

                        else -> throw IllegalArgumentException("Unknown option [--$name].")
                    }
                    i++
                }
                return Options(values, flags)
            }
        }
    }

    private fun convert(options: Options, out: PrintStream): Int {
        val fromRedis = options.value("from-redis")
        val fromFiles = options.value("from-files")
        if ((fromRedis == null) == (fromFiles == null)) {
            throw UsageException("Give exactly one of --from-redis and --from-files.")
        }
        val partition = partitionOf(options)
        val root = rootOf(options)
        val outDirectory = Path.of(options.required("out"))
        val read = if (fromRedis != null) {
            readRedis(fromRedis, options.value("key-prefix") ?: CoSec.COSEC)
        } else {
            ExportDirectory.read(Path.of(fromFiles!!))
        }
        val source = options.value("rate-limit-rules")?.let { file -> read.withRateLimitRules(ExportDirectory.rateLimitRules(Path.of(file))) } ?: read
        val conversion = Converter().convert(source, ConvertOptions(partition, root))
        ConvertedDirectory.write(conversion, outDirectory)
        summarize(conversion.report, out)
        out.println("Wrote ${outDirectory.resolve(ConvertedDirectory.REPORT_FILE)}")
        return if (conversion.report.accepted) OK else FAILED
    }

    private fun partitionOf(options: Options): Partition = when (val text = options.value("partition") ?: "platform") {
        "platform" -> Partition.PLATFORM
        "by-owner" -> Partition.BY_OWNER
        else -> throw UsageException("--partition is platform or by-owner, not [$text].")
    }

    /** Root is enabled by `--root-enabled`, or implied by `--root-id` or `--root-expires-at`. */
    private fun rootOf(options: Options): RootOptions? {
        val expiresAt = options.value("root-expires-at")?.let {
            try {
                Instant.parse(it)
            } catch (_: DateTimeParseException) {
                throw UsageException(
                    "--root-expires-at is an ISO-8601 instant, such as 2026-12-31T00:00:00Z, not [$it]."
                )
            }
        }
        val rootId = options.value("root-id")
        if (!options.flag("root-enabled") && expiresAt == null && rootId == null) {
            return null
        }
        return RootOptions(rootId ?: RootPrincipal.ROOT_ID, expiresAt)
    }

    private fun summarize(report: ConversionReport, out: PrintStream) {
        report.counts.forEach { (name, count) -> out.println("$name: $count") }
        report.semanticChanges.groupingBy { it.change }.eachCount().toSortedMap().forEach { (change, count) ->
            out.println("semantic change $change: $count place(s)")
        }
        report.warnings.forEach { out.println("WARN   ${it.location}: ${it.message}") }
        report.rejections.forEach { out.println("REJECT ${it.kind} ${it.location}: ${it.message} (${it.advice})") }
        out.println(
            if (report.accepted) {
                "Converted. Input sha256 ${report.inputSha256}."
            } else {
                "Rejected: fix the ${report.rejections.size} rejection(s) and convert again. Nothing else was written."
            },
        )
    }

    /** The 7.x data under [keyPrefix], with its schema marker, read with the same layout as 7.0. */
    fun readRedis(uri: String, keyPrefix: String): Source7x {
        val redisUri = RedisURI.create(uri)
        val client = RedisClient.create(redisUri)
        try {
            client.connect().use { connection ->
                val commands = connection.sync()
                val markerKey = "${keyPrefix.trimEnd(':')}:meta:schema"
                val marker = Source7x.Marker(markerKey, commands.get(markerKey))
                val keys = CoSec7xReader.keys(keyPrefix) { pattern -> scan(commands, pattern) }
                return RedisBackend.standalone(redisUri).use { backend ->
                    CoSec7xReader(backend, keyPrefix).use { reader -> reader.source(keys, marker) }
                }
            }
        } finally {
            client.shutdown()
        }
    }

    private fun scan(commands: RedisCommands<String, String>, pattern: String): Sequence<String> {
        val keys = ArrayList<String>()
        var cursor: ScanCursor = ScanCursor.INITIAL
        do {
            val result = commands.scan(cursor, ScanArgs.Builder.matches(pattern).limit(SCAN_COUNT))
            keys += result.keys
            cursor = result
        } while (!result.isFinished)
        return keys.asSequence()
    }

    private fun publish(options: Options, out: PrintStream): Int {
        options.required("to-redis")
        val input = Path.of(options.required("in"))
        val documents = ConvertedDirectory.read(input)
        val maxDuration = options.value("break-glass-max-duration")?.let {
            try {
                Duration.parse(it)
            } catch (_: DateTimeParseException) {
                throw UsageException("--break-glass-max-duration is an ISO-8601 duration, such as PT4H, not [$it].")
            }
        } ?: DocumentPublisher.DEFAULT_BREAK_GLASS_MAX_DURATION
        controlStore(options) { store ->
            val result = DocumentPublisher(
                store,
                breakGlassMaxDuration = maxDuration
            ).publish(documents, resume = options.flag("resume"))
            result.written.forEach { out.println("Published $it") }
            result.skipped.forEach { out.println("Already published, identical: $it") }
        }
        out.println("Published ${documents.size} document(s). The 7.x data is untouched.")
        return OK
    }

    private fun bootstrap(options: Options, out: PrintStream): Int {
        val principal = PrincipalId(options.required("platform-admin"))
        val revision = controlStore(options) { store -> Bootstrap(store).run(principal, options.flag("force")) }
        out.println(
            "Bound ${principal.value} of the platform tenant to cosec:platform-admin: ${Bootstrap.KEY} revision $revision."
        )
        return OK
    }

    private fun partitionPlan(options: Options, out: PrintStream): Int {
        val fromRedis = options.value("from-redis")
        val fromFiles = options.value("from-files")
        if ((fromRedis == null) == (fromFiles == null)) {
            throw UsageException("Give exactly one of --from-redis and --from-files.")
        }
        val source = if (fromRedis != null) {
            readRedis(
                fromRedis,
                options.value("key-prefix") ?: CoSec.COSEC
            )
        } else {
            ExportDirectory.read(Path.of(fromFiles!!))
        }
        val platform = Converter().convert(source, ConvertOptions(Partition.PLATFORM))
        val byOwner = Converter().convert(source, ConvertOptions(Partition.BY_OWNER))
        if (!platform.report.accepted || !byOwner.report.accepted) {
            summarize(if (platform.report.accepted) byOwner.report else platform.report, out)
            return FAILED
        }
        val written = PartitionPlan.write(PartitionPlan.of(platform, byOwner), Path.of(options.required("out")))
        written.forEach { out.println("Planned $it") }
        out.println("Planned ${written.size} tenant(s): submit each with apply (merge). Nothing was written to Redis.")
        return OK
    }

    /**
     * `api-keys` (RFC 0009 §4.4): converts the keys under `--api-key-prefix` to the format of RFC 0009 and writes the
     * change report. With `--dry-run`, or when a key is refused, nothing is written to Redis. Keys already converted are
     * left as they are, so a run can be repeated; each key is rewritten only while it still holds what was read.
     */
    private fun apiKeys(options: Options, out: PrintStream): Int {
        val uri = options.required("redis")
        val apiKeyPrefix = (options.value("api-key-prefix") ?: DEFAULT_API_KEY_PREFIX).trimEnd(':')
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
                val plan = controlStore(uri, options.value("key-prefix") ?: CoSec.COSEC) { store ->
                    ApiKeyMigration(
                        StoredDocuments(store),
                        memberships,
                        grantTo,
                        options.value("role-prefix") ?: ApiKeyMigration.DEFAULT_ROLE_PREFIX,
                    ).plan(stored.legacy)
                }.withRejections(stored.unreadable)
                val changed = if (plan.accepted && !dryRun) write(commands, apiKeyPrefix, plan) else emptyList()
                Files.createDirectories(outDirectory)
                val report = outDirectory.resolve(API_KEYS_REPORT_FILE)
                val json = plan.report(dryRun, stored.converted, changed)
                Files.write(report, CoSecJson.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(json))
                summarize(plan, out)
                out.println("Wrote $report")
                return outcome(plan, dryRun, changed, out)
            }
        } finally {
            client.shutdown()
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

    /** The control plane's store over the Redis of `--to-redis`, for [block]. */
    private fun <T> controlStore(options: Options, block: (CoCacheControlStore) -> T): T =
        controlStore(options.required("to-redis"), options.value("key-prefix") ?: CoSec.COSEC, block)

    /** The control plane's store over the Redis at [uri], for [block]. */
    private fun <T> controlStore(uri: String, keyPrefix: String, block: (CoCacheControlStore) -> T): T {
        val redisUri = RedisURI.create(uri)
        return RedisBackend.standalone(redisUri).use { backend ->
            CacheRuntime.builder().backend(backend).build().use { runtime ->
                val heads = runtime.createPolicySnapshotCache(CoSecCacheSpecs.policySnapshot(keyPrefix))
                block(CoCacheControlStore.create(runtime, heads, keyPrefix))
            }
        }
    }
}
