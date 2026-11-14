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
import io.lettuce.core.cluster.RedisClusterClient
import io.lettuce.core.codec.ByteArrayCodec
import kotlinx.coroutines.runBlocking
import me.ahoo.cocache.core.CacheRuntime
import me.ahoo.cocache.core.CoCacheFactory
import me.ahoo.cocache.redis.RedisBackend
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.api.CoSec
import me.ahoo.cosec.cache.ApiKeyCodec
import me.ahoo.cosec.cache.CoSecCacheSpecs
import me.ahoo.cosec.cache.control.CoCacheControlStore
import me.ahoo.cosec.cache.snapshot.createPolicySnapshotCache
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.runtime.json.CoSecJson
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
import kotlin.system.exitProcess

/**
 * `cosec-migrate`: converts CoSec 7.x policy data into 8.0 documents and publishes them (RFC 0004 §8.2).
 *
 * ```
 * convert --from-redis redis://localhost:6379 [--key-prefix cosec] --out ./converted
 *         [--partition platform|by-owner]
 * convert --from-files ./export --out ./converted [...]
 *         [--rate-limit-rules ./rate-limit.json]
 * publish --to-redis redis://localhost:6379 [--cluster] [--key-prefix cosec] --in ./converted [--resume]
 * bootstrap --to-redis redis://localhost:6379 [--cluster] [--key-prefix cosec] --platform-admin alice [--force]
 * repair-references --to-redis redis://localhost:6379 [--cluster] [--key-prefix cosec]
 * partition plan (--from-redis <uri> [--key-prefix cosec] | --from-files ./export) --out ./plan
 * api-keys --redis redis://localhost:6379 [--key-prefix cosec] --out ./api-keys
 *          [--memberships ./memberships.json] [--grant-to-principal <ids>|all] [--role-prefix api-key.] [--dry-run]
 * cocache8 --redis redis://localhost:6379 [--cluster] [--key-prefix cosec] --out ./documents [--discard-pending] [--dry-run]
 * ```
 *
 * `publish` and `bootstrap` write through the control plane's store, so each document's revision chain starts with
 * what they write (RFC 0007 §5.9). `repair-references` brings the back-reference index in line with what the heads refer
 * to ([ReferenceRepair]). With `--cluster`, `--to-redis` names seed nodes of a Redis Cluster, comma-separated.
 *
 * It writes no break-glass binding (RC1-27): `publish` and `bootstrap` refuse documents that hold one, and a 7.x root
 * is not converted. After the switch, break-glass is requested through the control plane, by two people (RFC 0007 §8).
 *
 * Exit codes: 0 done; 1 the data was rejected, publishing failed, or a document's references could not be repaired
 * (see `report.json` and the console); 2 usage.
 */
object MigrateMain {
    const val OK = 0
    const val FAILED = 1
    const val USAGE = 2
    private const val SCAN_COUNT = 1000L

    /**
     * The options each command reads. One it does not read is a usage error, before anything is read or written: it
     * would otherwise be accepted and ignored, as `publish --dry-run` was, which published for real (#1225). `--cluster`
     * is for the commands that write through the control plane's store; the others use plain Redis clients.
     */
    private val OPTIONS = mapOf(
        "convert" to setOf("from-redis", "from-files", "key-prefix", "out", "partition", "rate-limit-rules"),
        "publish" to setOf("to-redis", "cluster", "key-prefix", "in", "resume"),
        "bootstrap" to setOf("to-redis", "cluster", "key-prefix", "platform-admin", "force"),
        "repair-references" to setOf("to-redis", "cluster", "key-prefix"),
        "partition plan" to setOf("from-redis", "from-files", "key-prefix", "out"),
        "api-keys" to setOf(
            "redis", "key-prefix", "out", "memberships", "grant-to-principal", "role-prefix", "dry-run"
        ),
        "cocache8" to setOf("redis", "cluster", "key-prefix", "out", "discard-pending", "dry-run"),
    )
    const val API_KEYS_REPORT_FILE = "api-keys-report.json"
    private const val STATUS_PLANNED = "planned"
    private val REPLACEABLE_STATUS = setOf(ApiKeyMigrationPlan.STATUS_DRY_RUN, ApiKeyMigrationPlan.STATUS_REJECTED)

    private val USAGE_TEXT = """
        Usage:
          convert (--from-redis <uri> [--key-prefix <p>] | --from-files <dir>) --out <dir>
                  [--partition platform|by-owner]
                  [--rate-limit-rules <file>]
          publish --to-redis <uri> [--cluster] [--key-prefix <p>] --in <dir> [--resume]
          bootstrap --to-redis <uri> [--cluster] [--key-prefix <p>] --platform-admin <principal> [--force]
          repair-references --to-redis <uri> [--cluster] [--key-prefix <p>]
          (--cluster: --to-redis names seed nodes of a Redis Cluster, comma-separated)
          partition plan (--from-redis <uri> [--key-prefix <p>] | --from-files <dir>) --out <dir>
          cocache8 --redis <uri> [--cluster] [--key-prefix <p>] --out <dir> [--discard-pending] [--dry-run]
          api-keys --redis <uri> [--key-prefix <p>] --out <dir> [--app <id>] [--memberships <file>]
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
            dispatch(command, options, out)
        } catch (error: UsageException) {
            out.println(error.message)
            out.println(USAGE_TEXT)
            USAGE
        } catch (error: Exception) {
            out.println("Failed: ${error.message}")
            FAILED
        }
    }

    private fun dispatch(command: String?, options: Options, out: PrintStream): Int {
        OPTIONS[command]?.let { allowed ->
            val unused = options.names - allowed
            if (unused.isNotEmpty()) {
                throw UsageException(
                    "$command does not take ${unused.sorted().joinToString { "--$it" }}" +
                        (if ("cluster" in unused) " (--cluster is for the commands that write through the control plane's store)" else "") +
                        ". It takes ${allowed.sorted().joinToString { "--$it" }}."
                )
            }
        }
        return command(command, options, out)
    }

    private fun command(command: String?, options: Options, out: PrintStream): Int = when (command) {
        "convert" -> convert(options, out)
        "publish" -> publish(options, out)
        "bootstrap" -> bootstrap(options, out)
        "repair-references" -> repairReferences(options, out)
        "partition plan" -> partitionPlan(options, out)
        "api-keys" -> apiKeys(options, out)
        "cocache8" -> cocache8(options, out)
        else -> {
            out.println(USAGE_TEXT)
            USAGE
        }
    }

    private class UsageException(message: String) : IllegalArgumentException(message)

    /** `--name value` pairs and `--flag`s; a repeated or unknown option is a usage error. */
    private class Options(private val values: Map<String, String>, private val flags: Set<String>) {
        fun value(name: String): String? = values[name]

        fun required(name: String): String = values[name] ?: throw UsageException("Missing --$name.")

        fun flag(name: String): Boolean = name in flags

        /** Every option given, valued or not. */
        val names: Set<String> get() = values.keys + flags

        companion object {
            private val VALUED = setOf(
                "from-redis", "from-files", "key-prefix", "out", "partition", "to-redis", "in",
                "rate-limit-rules", "platform-admin", "redis", "role-prefix", "app",
                "memberships", "grant-to-principal",
            )
            private val FLAGS = setOf("resume", "force", "dry-run", "cluster", "discard-pending")

            /** The options that wrote break-glass, gone with it (RC1-27): what to do instead. */
            private val REMOVED = setOf("root-enabled", "root-id", "root-expires-at", "break-glass-max-duration")

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

                        in REMOVED -> throw IllegalArgumentException(
                            "--$name was removed: cosec-migrate writes no break-glass binding. After the switch, " +
                                "request break-glass through the control plane, by two people."
                        )

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
        val outDirectory = Path.of(options.required("out"))
        val read = if (fromRedis != null) {
            readRedis(fromRedis, options.value("key-prefix") ?: CoSec.COSEC)
        } else {
            ExportDirectory.read(Path.of(fromFiles!!))
        }
        val source = options.value("rate-limit-rules")?.let { file -> read.withRateLimitRules(ExportDirectory.rateLimitRules(Path.of(file))) } ?: read
        val conversion = Converter().convert(source, ConvertOptions(partition))
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
                return client.connect(ByteArrayCodec.INSTANCE).use { raw ->
                    CoSec7xReader(raw.sync(), keyPrefix).source(keys, marker)
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
        val uri = options.required("to-redis")
        val keyPrefix = options.value("key-prefix") ?: CoSec.COSEC
        val cluster = options.flag("cluster")
        val input = Path.of(options.required("in"))
        val documents = ConvertedDirectory.read(input)
        val finished = try {
            controlStore(options) { store ->
                val result = DocumentPublisher(store).publish(documents, resume = options.flag("resume"))
                result.written.forEach { out.println("Published $it") }
                result.skipped.forEach { out.println("Already published, identical: $it") }
                // A cocache8 run that stopped after its deletions is finished once every document it listed has a head.
                rawRedis(uri, cluster) { redis ->
                    CoCache8Upgrade(redis, keyPrefix).finish { key -> runBlocking { store.head(key) } != null }
                }
            }
        } catch (error: PublishException) {
            out.println("Failed: ${error.message}")
            out.println(
                "Finish with: ${CoCache8Upgrade.resumeCommand(
                    uri,
                    keyPrefix,
                    cluster,
                    input.toAbsolutePath().toString()
                )}"
            )
            return FAILED
        }
        out.println("Published ${documents.size} document(s). The 7.x data is untouched.")
        if (finished) {
            out.println("The cocache8 run that stopped is finished: every document it deleted is published again.")
        }
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

    /** `repair-references`: every document visited, every failed one listed; any failure exits with [FAILED]. */
    private fun repairReferences(options: Options, out: PrintStream): Int {
        val result = controlStore(options) { store -> ReferenceRepair(store).run() }
        result.failed.forEach { (key, message) -> out.println("FAILED $key: $message") }
        out.println("Visited ${result.documents} document(s); added ${result.added} reference(s).")
        out.println("Removed ${result.removed} reference(s) no head refers to.")
        result.apps?.let { out.println("The app list is complete: $it app(s).") }
            ?: out.println("FAILED the app list could not be completed: run again.")
        if (result.failed.isNotEmpty() || result.apps == null) {
            out.println("Not everything was repaired: fix what failed and run again.")
            return FAILED
        }
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
        val plan = PartitionPlan.of(platform, byOwner)
        val written = PartitionPlan.write(plan, Path.of(options.required("out")))
        plan.keys.zip(written).forEach { (key, path) -> out.println("Planned $key: $path") }
        out.println("Planned ${written.size} tenant(s): submit each with apply (merge). Nothing was written to Redis.")
        return OK
    }

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
    private fun apiKeys(options: Options, out: PrintStream): Int {
        val uri = options.required("redis")
        val keyPrefix = options.value("key-prefix") ?: CoSec.COSEC
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

    /** The control plane's store over the Redis of `--to-redis` (seed nodes of a cluster with `--cluster`), for [block]. */
    private fun <T> controlStore(options: Options, block: (CoCacheControlStore) -> T): T =
        controlStore(
            options.required("to-redis"),
            options.value("key-prefix") ?: CoSec.COSEC,
            options.flag("cluster"),
            block
        )

    /** The control plane's store over the Redis at [uri], or the Redis Cluster [uri] names seed nodes of, for [block]. */
    private fun <T> controlStore(
        uri: String,
        keyPrefix: String,
        cluster: Boolean = false,
        block: (CoCacheControlStore) -> T
    ): T = factory(uri, cluster) { factory ->
        val heads = factory.createPolicySnapshotCache(CoSecCacheSpecs.policySnapshot(keyPrefix))
        block(CoCacheControlStore.create(factory, heads, keyPrefix))
    }

    /** CoCache 8 over the Redis at [uri], or the Redis Cluster [uri] names seed nodes of, for [block]. */
    private fun <T> factory(uri: String, cluster: Boolean, block: (CoCacheFactory) -> T): T {
        val backend = if (cluster) {
            RedisBackend.cluster(uri.split(',').map { RedisURI.create(it.trim()) })
        } else {
            RedisBackend.standalone(RedisURI.create(uri))
        }
        return backend.use { backend ->
            CacheRuntime.builder().backend(backend).build().use { runtime -> block(CoCacheFactory(runtime)) }
        }
    }

    /**
     * `cocache8` (a CoSec 8.0.0-alpha deployment, every instance stopped): reads every CoCache 6 value of CoSec's
     * families and refuses, writing nothing, what cannot be moved; else writes the documents to `--out` (the input of
     * `publish`, to finish a run that stops after it deleted them), then the revocations, deletions and documents.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun cocache8(options: Options, out: PrintStream): Int {
        val uri = options.required("redis")
        val keyPrefix = options.value("key-prefix") ?: CoSec.COSEC
        val cluster = options.flag("cluster")
        val output = Path.of(options.required("out"))
        val (plan, problems) = rawRedis(uri, cluster) { redis ->
            val upgrade = CoCache8Upgrade(redis, keyPrefix)
            val plan = upgrade.plan()
            plan to upgrade.check(plan, options.flag("discard-pending"))
        }
        out.println(
            "CoCache 6 values under [$keyPrefix]: ${plan.documents.size} document(s), " +
                "${plan.revocations.size} revocation(s), " +
                plan.deletions.entries.joinToString(prefix = "to delete: ") { (cache, keys) -> "$cache ${keys.size}" } +
                "; ${plan.skipped.size} key(s) not in CoCache 6's format, left as they are."
        )
        if (problems.isNotEmpty()) {
            problems.forEach { out.println("REFUSED $it") }
            plan.unfinished?.let {
                out.println("Finish it with: ${CoCache8Upgrade.resumeCommand(uri, keyPrefix, cluster, it.out)}")
            }
            out.println("Nothing was written.")
            return FAILED
        }
        if (options.flag("dry-run")) {
            out.println("Dry run: nothing was written.")
            return OK
        }
        val resume = CoCache8Upgrade.resumeCommand(uri, keyPrefix, cluster, output.toAbsolutePath().toString())
        if (plan.documents.isNotEmpty()) {
            out.println(
                "Writing the documents to $output first. If this run stops after it deleted them, finish with: $resume"
            )
        }
        val published = try {
            rawRedis(uri, cluster) { redis ->
                factory(uri, cluster) { factory ->
                    val heads = factory.createPolicySnapshotCache(CoSecCacheSpecs.policySnapshot(keyPrefix))
                    CoCache8Upgrade(redis, keyPrefix)
                        .apply(plan, factory, CoCacheControlStore.create(factory, heads, keyPrefix), output)
                }
            }
        } catch (error: Exception) {
            out.println("FAILED ${error.message}")
            if (plan.documents.isNotEmpty()) {
                out.println(
                    "Do not run cocache8 again to finish: it cannot see the documents it deleted. Finish with: $resume"
                )
            }
            return FAILED
        }
        out.println("Wrote ${plan.revocations.size} revocation(s) again, with what remains of their lifetimes.")
        out.println("Deleted ${plan.deletions.values.sumOf { it.size }} CoCache 6 value(s).")
        published?.written?.forEach { out.println("Published $it") }
        return OK
    }

    /** Raw commands on the Redis at [uri], or the Redis Cluster [uri] names seed nodes of, for [block]. */
    private fun <T> rawRedis(uri: String, cluster: Boolean, block: (RawRedis) -> T): T {
        if (cluster) {
            val client = RedisClusterClient.create(uri.split(',').map { RedisURI.create(it.trim()) })
            try {
                return client.connect(ByteArrayCodec.INSTANCE).use { connection ->
                    val commands = connection.sync()
                    block(LettuceRawRedis(commands, commands))
                }
            } finally {
                client.shutdown()
            }
        }
        val client = RedisClient.create(RedisURI.create(uri))
        try {
            return client.connect(ByteArrayCodec.INSTANCE).use { connection ->
                val commands = connection.sync()
                block(LettuceRawRedis(commands, commands))
            }
        } finally {
            client.shutdown()
        }
    }
}
