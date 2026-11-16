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
import io.lettuce.core.cluster.RedisClusterClient
import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.cocache.DEFAULT_KEY_PREFIX
import me.ahoo.cosec.cocache.control.CoCacheControlStore
import me.ahoo.cosec.document.PolicyDocument
import me.ahoo.cosec.document.PolicyDocumentCodec
import me.ahoo.cosec.document.json.CoSecJson
import me.ahoo.cosec.migrate.MigrateMain.FAILED
import me.ahoo.cosec.migrate.MigrateMain.OK
import me.ahoo.cosec.migrate.RedisAccess.controlStore
import me.ahoo.cosec.model.Identifiers
import me.ahoo.cosec.model.decision.DocumentKind
import me.ahoo.cosec.spi.snapshot.SnapshotKey
import tools.jackson.core.JacksonException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * `publish`: the converted directory of `convert` (`--in`, each file with the SHA-256 its `report.json` records), or one
 * document written by hand (`--file`, as the document `--document` names). Either way it writes through the control
 * plane's store, only where no document is yet, after the checks [DocumentPublisher] makes.
 */
internal object PublishCommand {
    /** The Redis it writes to, and the URIs whose credentials it leaves out of what it prints. */
    class Target(val uri: String, val keyPrefix: String, val cluster: Boolean, val redisUris: List<String>)

    /** What it publishes. */
    sealed interface Source {
        /** The output of `convert`, or finishing one that stopped with [resume]. */
        class Directory(val path: Path, val resume: Boolean) : Source

        /** One document written by hand, as [key]. */
        class File(val path: Path, val key: SnapshotKey) : Source
    }

    /** `platform`, `app:<id>` or `tenant:<id>`, as `--document` names one; null for anything else. */
    fun documentKey(text: String): SnapshotKey? = when {
        text == "platform" -> SnapshotKey.Platform
        text.startsWith(
            "app:"
        ) -> text.removePrefix("app:").takeIf(SnapshotKey.App::isValidAppId)?.let(SnapshotKey::App)
        text.startsWith("tenant:") ->
            text.removePrefix("tenant:").takeIf(SnapshotKey.Tenant::isValidTenantId)?.let(SnapshotKey::Tenant)
        else -> null
    }

    fun run(target: Target, source: Source, out: PrintStream, store: ((CoCacheControlStore) -> Unit) -> Unit): Int {
        val documents = when (source) {
            is Source.Directory -> ConvertedDirectory.read(source.path)
            is Source.File -> mapOf(source.key to read(source))
        }
        unfinishedUpgrade(target.uri, target.cluster, target.keyPrefix)?.let { refusal ->
            refusal.forEach(out::println)
            return MigrateMain.FAILED
        }
        try {
            store { controlStore ->
                val publisher = DocumentPublisher(controlStore)
                val result = when (source) {
                    is Source.Directory -> publisher.publish(documents, resume = source.resume)
                    is Source.File -> publisher.publishOne(source.key, documents.getValue(source.key))
                }
                result.written.forEach { out.println("Published $it") }
                result.skipped.forEach { out.println("Already published, identical: $it") }
            }
        } catch (error: PublishException) {
            out.println("Failed: ${RedisUris.scrub(error.message.orEmpty(), target.redisUris)}")
            if (source is Source.Directory) {
                out.println(
                    "Finish with (the credentials of --to-redis left out): cosec-migrate publish --to-redis " +
                        RedisUris.withoutUserInfo(target.uri) + " --key-prefix ${target.keyPrefix}" +
                        (if (target.cluster) " --cluster" else "") + " --in ${source.path.toAbsolutePath()} --resume"
                )
            }
            return MigrateMain.FAILED
        }
        if (source is Source.Directory) {
            out.println("Published ${documents.size} document(s). The 7.x data is untouched.")
        }
        return MigrateMain.OK
    }

    /** The document of [source], read as strictly as `convert`'s output is: any member it does not know is refused. */
    private fun read(source: Source.File): PolicyDocument {
        require(Files.isRegularFile(source.path)) { "${source.path} is not a file." }
        val (kind, owner) = when (val key = source.key) {
            SnapshotKey.Platform -> DocumentKind.PLATFORM to ""
            is SnapshotKey.App -> DocumentKind.APP to key.appId
            is SnapshotKey.Tenant -> DocumentKind.TENANT to key.tenantId
        }
        return PolicyDocumentCodec.decode(kind, owner, Files.readAllBytes(source.path))
    }

    /**
     * The refusal to write while `<prefix>:meta:cocache8` is there, null when it is not. 8.0.0-alpha's CoCache 6 → 8
     * upgrade wrote that marker after it exported the documents and before it deleted them, and removed it once every
     * one was published again: one left means a run that stopped half-way, whose documents are in its export, not in
     * Redis. This tool no longer has that upgrade, and its documents are in alpha.4's layout, so it does not finish it:
     * publishing over it would hide that some documents are missing, and nothing at run time reads the marker.
     */
    private fun unfinishedUpgrade(uri: String, cluster: Boolean, keyPrefix: String): List<String>? {
        val key = "${keyPrefix.trimEnd(':')}:meta:cocache8"
        val marker = redisValue(uri, cluster, key) ?: return null
        val export = try {
            // Read from Redis, not written by this tool: printed so that it cannot drive the terminal.
            CoSecJson.mapper.readTree(marker).get("out")?.asString()?.let(Identifiers::printable)
        } catch (_: JacksonException) {
            null
        }
        return listOf(
            "Refused: [$key] marks a CoCache 6 to 8 upgrade of 8.0.0-alpha that stopped after it deleted the documents " +
                "and before it published them all again${export?.let { " (its export: $it)" }.orEmpty()}.",
            "Finish it with the cosec-migrate of 8.0.0-alpha.4, the last one that has the upgrade: publish --resume " +
                "with --in on its export. Or check that every document it lists is published, then delete [$key].",
            "Nothing was written.",
        )
    }

    /** The value of [key] on the Redis at [uri], or the Redis Cluster [uri] names seed nodes of; null when it has none. */
    private fun redisValue(uri: String, cluster: Boolean, key: String): String? {
        if (cluster) {
            val client = RedisClusterClient.create(uri.split(',').map { RedisURI.create(it.trim()) })
            try {
                return client.connect().use { it.sync().get(key) }
            } finally {
                client.shutdown()
            }
        }
        val client = RedisClient.create(RedisURI.create(uri))
        try {
            return client.connect().use { it.sync().get(key) }
        } finally {
            client.shutdown()
        }
    }

    fun publish(options: Options, out: PrintStream): Int {
        val file = options.value("file")
        if (file != null && (options.value("in") != null || options.flag("resume"))) {
            throw UsageException("publish takes --file or --in, not both; --resume is for --in.")
        }
        if (file == null && options.value("document") != null) {
            throw UsageException("--document names the document of --file.")
        }
        val source = file?.let { fileSource(it, options.required("document")) }
            ?: PublishCommand.Source.Directory(Path.of(options.required("in")), options.flag("resume"))
        val target = PublishCommand.Target(
            options.required("to-redis"),
            options.value("key-prefix") ?: DEFAULT_KEY_PREFIX,
            options.flag("cluster"),
            options.redisUris,
        )
        return PublishCommand.run(target, source, out) { block -> controlStore(options, block) }
    }

    private fun fileSource(file: String, document: String) = PublishCommand.Source.File(
        Path.of(file),
        PublishCommand.documentKey(document) ?: throw UsageException(
            "--document is platform, app:<id> or tenant:<id>, not [${Identifiers.printable(document)}]."
        ),
    )
}
