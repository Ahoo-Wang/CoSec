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

/**
 * `--name value` and `--name=value` pairs and `--flag`s; a repeated or unknown option is a usage error, and so is a
 * value that starts with `--` (the next option, with its value forgotten): write `--name=--value` for such a value.
 * With [readable], reading an option outside it is a defect of the command, never a usage error.
 */
internal class Options(
    private val values: Map<String, String>,
    private val flags: Set<String>,
    private val readable: Set<String>? = null,
) {
    fun value(name: String): String? = values[readable(name)]

    fun required(name: String): String = values[readable(name)] ?: throw UsageException("Missing --$name.")

    fun flag(name: String): Boolean = readable(name) in flags

    /** These options, of which the command may read only [names]: its table in [OPTIONS]. */
    fun readableOnly(names: Set<String>): Options = Options(values, flags, names)

    private fun readable(name: String): String {
        check(readable == null || name in readable) { "The command reads --$name, which its table does not list." }
        return name
    }

    /** Every option given, valued or not. */
    val names: Set<String> get() = values.keys + flags

    /**
     * The Redis URIs given, whatever the command reads: only so that nothing printed shows their credentials
     * ([RedisUris]), never as input.
     */
    val redisUris: List<String> get() = REDIS_URIS.mapNotNull { values[it] }

    companion object {
        private val VALUED = setOf(
            "from-redis", "from-files", "key-prefix", "out", "partition", "to-redis", "in",
            "rate-limit-rules", "platform-admin", "redis", "role-prefix", "app",
            "memberships", "grant-to-principal", "file", "document", "key",
        )
        private val FLAGS = setOf("resume", "force", "dry-run", "cluster")
        private val REDIS_URIS = listOf("to-redis", "from-redis", "redis")

        /** The options that wrote break-glass, gone with it (RC1-27): what to do instead. */
        private val REMOVED = setOf("root-enabled", "root-id", "root-expires-at", "break-glass-max-duration")

        fun parse(args: List<String>): Options {
            val values = HashMap<String, String>()
            val flags = HashSet<String>()
            var i = 0
            while (i < args.size) {
                require(args[i].startsWith("--")) { "Unexpected argument [${args[i]}]." }
                val (name, attached) = args[i].substring(2).split('=', limit = 2).let { it[0] to it.getOrNull(1) }
                when (name) {
                    in FLAGS -> {
                        require(attached == null) { "--$name takes no value." }
                        require(flags.add(name)) { "--$name is given twice." }
                    }
                    in VALUED -> {
                        val value = attached ?: args.getOrNull(i + 1)?.takeUnless { it.startsWith("--") }?.also { i++ }
                        require(value != null) { "--$name needs a value (--$name=value, if it starts with --)." }
                        require(values.put(name, value) == null) { "--$name is given twice." }
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
