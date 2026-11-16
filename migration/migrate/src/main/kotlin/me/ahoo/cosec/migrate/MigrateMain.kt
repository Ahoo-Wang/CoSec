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

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.annotations.InternalCoSecApi
import java.io.PrintStream
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
 * publish --to-redis redis://localhost:6379 [--cluster] [--key-prefix cosec] --file ./platform.json --document platform
 * bootstrap --to-redis redis://localhost:6379 [--cluster] [--key-prefix cosec] --platform-admin alice [--force]
 * repair-references --to-redis redis://localhost:6379 [--cluster] [--key-prefix cosec]
 * partition plan (--from-redis <uri> [--key-prefix cosec] | --from-files ./export) --out ./plan
 * api-keys --redis redis://localhost:6379 [--key-prefix cosec] --out ./api-keys
 *          [--memberships ./memberships.json] [--grant-to-principal <ids>|all] [--role-prefix api-key.] [--dry-run]
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

    /**
     * The options each command reads. One it does not read is a usage error, before anything is read or written: it
     * would otherwise be accepted and ignored, as `publish --dry-run` was, which published for real (#1225). `--cluster`
     * is for the commands that write through the control plane's store; the others use plain Redis clients.
     */
    internal val OPTIONS = mapOf(
        "convert" to setOf("from-redis", "from-files", "key-prefix", "out", "partition", "rate-limit-rules"),
        "publish" to setOf("to-redis", "cluster", "key-prefix", "in", "resume", "file", "document"),
        "bootstrap" to setOf("to-redis", "cluster", "key-prefix", "platform-admin", "force"),
        "repair-references" to setOf("to-redis", "cluster", "key-prefix"),
        "partition plan" to setOf("from-redis", "from-files", "key-prefix", "out"),
        "api-keys" to setOf(
            "redis", "key-prefix", "out", "app", "memberships", "grant-to-principal", "role-prefix", "dry-run"
        ),
    )
    const val API_KEYS_REPORT_FILE = "api-keys-report.json"
    internal val USAGE_TEXT = """
        Usage:
          convert (--from-redis <uri> [--key-prefix <p>] | --from-files <dir>) --out <dir>
                  [--partition platform|by-owner]
                  [--rate-limit-rules <file>]
          publish --to-redis <uri> [--cluster] [--key-prefix <p>] --in <dir> [--resume]
                  or, in place of --in: --file <document.json> --document platform|app:<id>|tenant:<id>
          bootstrap --to-redis <uri> [--cluster] [--key-prefix <p>] --platform-admin <principal> [--force]
          repair-references --to-redis <uri> [--cluster] [--key-prefix <p>]
          (--cluster: --to-redis names seed nodes of a Redis Cluster, comma-separated)
          partition plan (--from-redis <uri> [--key-prefix <p>] | --from-files <dir>) --out <dir>
          api-keys --redis <uri> [--key-prefix <p>] --out <dir> [--app <id>] [--memberships <file>]
                   [--grant-to-principal <ids>|all] [--role-prefix <p>] [--dry-run]
    """.trimIndent()

    @JvmStatic
    fun main(args: Array<String>) {
        exitProcess(run(args.toList(), System.out))
    }

    @Suppress("TooGenericExceptionCaught")
    fun run(args: List<String>, out: PrintStream): Int {
        // What the tool prints of an error leaves out the credentials of every URI it was given ([RedisUris]).
        val uris = args.filter { "://" in it }
        fun printed(error: Throwable): String = RedisUris.scrub(error.message ?: error.javaClass.name, uris)
        val command = args.firstOrNull().let { if (it == "partition" && args.getOrNull(1) == "plan") "partition plan" else it }
        val options = try {
            Options.parse(args.drop(if (command == "partition plan") 2 else 1))
        } catch (error: IllegalArgumentException) {
            out.println(printed(error))
            out.println(USAGE_TEXT)
            return USAGE
        }
        return try {
            dispatch(command, options, out)
        } catch (error: UsageException) {
            out.println(printed(error))
            out.println(USAGE_TEXT)
            USAGE
        } catch (error: Exception) {
            out.println("Failed: ${printed(error)}")
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
        // What a command reads is in its table (round 2 M3): reading another option fails, so a test that runs the
        // command finds it, instead of an operator whose option is refused.
        return command(command, OPTIONS[command]?.let(options::readableOnly) ?: options, out)
    }

    private fun command(command: String?, options: Options, out: PrintStream): Int = when (command) {
        "convert" -> ConvertCommand.convert(options, out)
        "publish" -> PublishCommand.publish(options, out)
        "bootstrap" -> BootstrapCommand.bootstrap(options, out)
        "repair-references" -> RepairReferencesCommand.repairReferences(options, out)
        "partition plan" -> PartitionPlanCommand.partitionPlan(options, out)
        "api-keys" -> ApiKeysCommand.apiKeys(options, out)
        else -> {
            out.println(USAGE_TEXT)
            USAGE
        }
    }
}
