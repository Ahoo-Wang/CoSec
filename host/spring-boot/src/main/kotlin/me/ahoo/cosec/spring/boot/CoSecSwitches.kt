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

package me.ahoo.cosec.spring.boot

import me.ahoo.cosec.annotations.InternalCoSecApi
import org.springframework.core.env.Environment

/**
 * UB-1: the `cosec.*` switches the starter's `@ConditionalOnProperty` conditions read, and what each accepts.
 *
 * A condition reads `havingValue = "true"` as "on" and any other written value, `1`, `on`, an empty string, as "off",
 * while Spring's binder reads the same value as true, or as unset. So a value that is not exactly what the condition
 * compares switches a feature off with no word, or, for `cosec.pipeline.mode`, wires no pipeline at all. This check
 * fails startup for such a value, naming the key, the value found and the values accepted; an absent key is its
 * default. A switch that is legitimately `false` stays off, and the startup summary says so.
 *
 * The table is the fact of the build, held in code: the running application does no scanning. `CoSecSwitchesTest` scans
 * the starter's compiled classes for `@ConditionalOnProperty` and fails when this table and the conditions differ, in
 * either direction. The custom conditions that read the environment themselves ([me.ahoo.cosec.spring.boot.control.ControlPlaneCondition],
 * `apiKeysEnabled`, `kafkaAuditDelivery`) parse strictly and name their property; they are not in the table.
 */
internal object CoSecSwitches {
    const val DOCUMENTATION = "https://cosec.ahoo.me/operations/configuration"

    private val BOOLEAN = listOf("true", "false")

    /** One switch: its [key] and the [accepted] values, compared as the condition compares: ignoring case, never trimmed. */
    data class Switch(val key: String, val accepted: List<String>)

    private fun booleans(vararg keys: String) = keys.map { Switch(it, BOOLEAN) }

    val ALL: List<Switch> = booleans(
        "cosec.enabled",
        "cosec.jwt.enabled",
        "cosec.authentication.login.enabled",
        "cosec.authentication.login.social.enabled",
        "cosec.authentication.token-cache.enabled",
        "cosec.openapi.enabled",
        "cosec.audit.enabled",
        "cosec.audit.kafka.enabled",
        "cosec.metrics.enabled",
        "cosec.ip2region.enabled",
        "cosec.authorization.shadow.enabled",
        "cosec.authorization.snapshot.prewarm-host-apps",
        "cosec.gateway.identity.enabled",
        "cosec.control.audit.kafka.enabled",
        "cosec.api-key.enabled",
    ) + listOf(
        Switch("cosec.pipeline.mode", listOf("enforce", "gateway")),
        Switch("cosec.pipeline.caller", listOf("credential", "forwarded-identity")),
        Switch("cosec.api-key.store", listOf("memory", "redis")),
    )

    /** A switch written with a value it does not accept. */
    data class Unreadable(val switch: Switch, val value: String) {
        override fun toString(): String =
            "  ${switch.key}=[$value]: write ${switch.accepted.joinToString(" or ")}"
    }

    /** The switches of [environment] written with a value they do not accept, in table order. */
    fun unreadable(environment: Environment): List<Unreadable> = ALL.mapNotNull { switch ->
        val value = environment.getProperty(switch.key) ?: return@mapNotNull null
        Unreadable(switch, value).takeIf { switch.accepted.none { it.equals(value, ignoreCase = true) } }
    }

    /** The boolean switches of [environment] written `false`: what the configuration switched off on purpose. */
    fun switchedOff(environment: Environment): List<String> = ALL.filter { it.accepted == BOOLEAN }
        .map { it.key }
        .filter { environment.getProperty(it).equals("false", ignoreCase = true) }

    /** Fails startup, with every unreadable switch in one message, when [unreadable] is not empty. */
    fun check(environment: Environment) {
        val problems = unreadable(environment)
        if (problems.isEmpty()) {
            return
        }
        error(
            "CoSec cannot read ${problems.size} cosec.* switch${if (problems.size == 1) "" else "es"}; a value it " +
                "cannot read would switch the feature off without a word:\n" + problems.joinToString("\n") +
                "\nSet each to one of its values, or remove it to take its default: $DOCUMENTATION"
        )
    }
}
