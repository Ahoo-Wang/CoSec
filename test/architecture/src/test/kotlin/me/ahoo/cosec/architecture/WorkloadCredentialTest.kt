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

package me.ahoo.cosec.architecture

import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

/**
 * `IDN-34` across every module, from the compiled classes: what a principal may do as a person or as a workload is read
 * from `AuthenticatedPrincipal.workload`, the one definition of a workload's credential (an API key, or a credential
 * named `service`). No main class reads `apiKey` but `AuthenticatedPrincipal` itself, where `workload` is made of it: a
 * reader that decided on `apiKey` alone would take a service's own identity for a person, which may approve a change or
 * be the control plane's trust root.
 */
class WorkloadCredentialTest {
    private val classes = ClassFileImporter().withImportOption(DoNotIncludeTests()).importPackages("me.ahoo.cosec")

    @Test
    fun `whether a principal is a workload is read from workload, never from apiKey alone - IDN-34`() {
        val calls = classes.flatMap { it.methodCallsFromSelf }.filter { it.targetOwner.name == PRINCIPAL }
        // The rule sees what it guards: the control plane reads workload, for approvals and the trust root.
        calls.filter { it.target.name == WORKLOAD }.map { it.originOwner.name }.assert()
            .describedAs("the readers of workload").isNotEmpty()
        val alone = calls.filter { it.target.name == API_KEY && !OWN.matches(it.originOwner.name) }.map(::shown)
        alone.assert().describedAs("readers of AuthenticatedPrincipal.apiKey: read workload instead (IDN-34)").isEmpty()
    }

    private fun shown(call: JavaMethodCall): String = "${call.origin.fullName} calls ${call.target.name}"

    private companion object {
        const val PRINCIPAL = "me.ahoo.cosec.spi.authn.AuthenticatedPrincipal"
        const val API_KEY = "getApiKey"
        const val WORKLOAD = "getWorkload"
        val OWN = Regex("""me\.ahoo\.cosec\.spi\.authn\.AuthenticatedPrincipal(\$.*)?""")
    }
}
