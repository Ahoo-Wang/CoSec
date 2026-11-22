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

@file:OptIn(ExperimentalCoSecApi::class)

package com.example.iam

import me.ahoo.cosec.annotations.ExperimentalCoSecApi
import me.ahoo.cosec.api.context.SecurityContext
import me.ahoo.cosec.control.sdk.Binding
import me.ahoo.cosec.control.sdk.Change
import me.ahoo.cosec.control.sdk.ControlCaller
import me.ahoo.cosec.control.sdk.ControlClient
import me.ahoo.cosec.control.sdk.Effect
import me.ahoo.cosec.control.sdk.Operation
import me.ahoo.cosec.control.sdk.Policy
import me.ahoo.cosec.control.sdk.Resource
import me.ahoo.cosec.control.sdk.Statement
import me.ahoo.cosec.control.sdk.Subject
import me.ahoo.cosec.spi.snapshot.SnapshotKey

/**
 * An IAM service writing what it already decided into a tenant's document, in typed values: the auditors of [tenant]
 * read its orders. The change is proposed, staged without a shadow window and published in one call, decided for the
 * user whose request the service is serving and recorded as theirs.
 */
class AuditorsProjection(private val control: ControlClient) {
    suspend fun auditorsReadOrders(context: SecurityContext, tenant: String): Change =
        control.proposeAndPublishWithoutShadow(
            ControlCaller.of(context),
            SnapshotKey.Tenant(tenant),
            listOf(
                Operation.PutPolicy(
                    Policy("audit-read", listOf(Statement(Effect.ALLOW, listOf("order:read"), listOf(Resource("order/**"))))),
                ),
                Operation.PutBinding(Binding("auditors", Subject.Role("auditor"), "order", "*", "audit-read")),
            ),
            note = "auditors read orders",
            skipReason = "decided in the IAM, which keeps its own approval",
        )
}
