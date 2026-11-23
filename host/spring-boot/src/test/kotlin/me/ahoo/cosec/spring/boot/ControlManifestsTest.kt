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

package me.ahoo.cosec.spring.boot

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.YamlProcessor
import org.springframework.core.io.FileSystemResource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * The manifests of the control plane's host (`k8s/control/`) are held to the image they run. The image will not start
 * without the statement that nothing but the gateway and the services the operator names can reach it. That is true only
 * if the cluster's network plugin enforces the policies, which no file can say, so `k8s/control/` does not state it: the
 * overlay does, and the Job `boundary-check.yaml` checks it on the cluster (DR1-G-1). What files can hold is checked here,
 * not against a cluster (the kind leg of the system test runs the check both ways):
 *
 * - the three ports of the Deployment are the image's (`platform/control-server`'s `application.yaml`), and the probes and
 *   the Services use them by name;
 * - there is one NetworkPolicy per port, each selects the Deployment's pods, and each admits a named source: the gateway's
 *   pods to the operators' port, the services the operator names to the service port, a monitoring namespace to the
 *   management port. No rule admits every source or every port, and no Service publishes the management port;
 * - the policies and the check are resources of the kustomization every overlay builds, the check comes from a pod no policy
 *   admits and tries every port of every pod, ready or not, and an overlay states the boundary only beside it.
 */
class ControlManifestsTest {
    private val repository: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.exists(it.resolve("settings.gradle.kts")) }
    private val control = repository.resolve("k8s/control")

    private fun documents(file: Path): List<MutableMap<String, Any?>> {
        Files.exists(file).assert().describedAs("$file exists").isTrue()
        val processor = object : YamlProcessor() {
            fun all(): List<MutableMap<String, Any?>> = buildList {
                process { _, map ->
                    @Suppress("UNCHECKED_CAST")
                    add(map as MutableMap<String, Any?>)
                }
            }
        }
        processor.setResources(FileSystemResource(file))
        return processor.all()
    }

    private fun load(): ControlManifests {
        val image = documents(
            repository.resolve("platform/control-server/src/main/resources/application.yaml")
        ).single()
        val gateway = documents(repository.resolve("k8s/base/deployment.yaml")).single()
        return ControlManifests(
            deployment = documents(control.resolve("deployment.yaml")).single(),
            services = documents(control.resolve("service.yaml")),
            check = documents(control.resolve("boundary-check.yaml")).single(),
            policies = listOf("operators", "services", "management")
                .map { documents(control.resolve("networkpolicy-$it.yaml")).single() },
            gatewayLabels = gateway.at("spec", "template", "metadata", "labels").strings(),
            image = mapOf(
                "http" to image.at("server", "port") as Int,
                "management" to image.at("management", "server", "port") as Int,
                "service" to image.at("cosec", "control", "http", "service-port") as Int
            )
        )
    }

    @Test
    fun `the manifests as shipped have nothing to report`() {
        load().problems().assert().isEmpty()
    }

    @Test
    fun `a port the image does not serve is reported`() {
        val manifests = load()
        manifests.container().ports().first { it["name"] == "management" }["containerPort"] = 9090
        manifests.problems().assert().anyMatch { it.contains("management") && it.contains("9090") }
    }

    @Test
    fun `a port with no NetworkPolicy of its own is reported`() {
        val manifests = load()
        val services = manifests.policies.first { it.at("metadata", "name") == "cosec-control-services" }
        (services.at("spec", "ingress").arr().single().obj())["ports"] = listOf(mapOf("port" to "http", "protocol" to "TCP"))
        manifests.problems().assert()
            .anyMatch { it.contains("port service") && it.contains("no NetworkPolicy") }
            .anyMatch { it.contains("http") && it.contains("more than one") }
    }

    @Test
    fun `a policy that admits every source or every port is reported`() {
        val everySource = load()
        val operators = everySource.policies.first { it.at("metadata", "name") == "cosec-control-operators" }
        operators.at("spec", "ingress").arr().single().obj().remove("from")
        everySource.problems().assert().anyMatch { it.contains("cosec-control-operators") && it.contains("every source") }

        val everyPort = load()
        val management = everyPort.policies.first { it.at("metadata", "name") == "cosec-control-management" }
        management.at("spec", "ingress").arr().single().obj().remove("ports")
        everyPort.problems().assert().anyMatch { it.contains("cosec-control-management") && it.contains("every port") }

        val emptySelector = load()
        val services = emptySelector.policies.first { it.at("metadata", "name") == "cosec-control-services" }
        services.at("spec", "ingress").arr().single().obj()["from"] = listOf(mapOf("podSelector" to mutableMapOf<String, Any>()))
        emptySelector.problems().assert().anyMatch { it.contains("cosec-control-services") && it.contains("empty selector") }
    }

    @Test
    fun `a policy that selects other pods, or admits the wrong pods to a port, is reported`() {
        val other = load()
        val management = other.policies.first { it.at("metadata", "name") == "cosec-control-management" }
        management.at("spec", "podSelector").obj()["matchLabels"] = mapOf("app.kubernetes.io/name" to "cosec-gateway")
        other.problems().assert().anyMatch { it.contains("cosec-control-management") && it.contains("does not select") }

        val wrongOperators = load()
        val operators = wrongOperators.policies.first { it.at("metadata", "name") == "cosec-control-operators" }
        operators.at("spec", "ingress").arr().single().obj()["from"] = listOf(
            mapOf("podSelector" to mapOf("matchLabels" to mapOf("app.kubernetes.io/name" to "someone-else")))
        )
        wrongOperators.problems().assert().anyMatch { it.contains("operators' port") && it.contains("gateway") }

        val gatewayOnServices = load()
        val services = gatewayOnServices.policies.first { it.at("metadata", "name") == "cosec-control-services" }
        services.at("spec", "ingress").arr().single().obj()["from"] = listOf(
            mapOf(
                "namespaceSelector" to mapOf("matchLabels" to mapOf("kubernetes.io/metadata.name" to "cosec")),
                "podSelector" to mapOf("matchLabels" to mapOf("app.kubernetes.io/name" to "cosec-gateway"))
            )
        )
        gatewayOnServices.problems().assert().anyMatch { it.contains("service port") && it.contains("gateway's pods") }
    }

    @Test
    fun `a Service that publishes the management port is reported`() {
        val manifests = load()
        val ports = manifests.services.first().at("spec", "ports").arr()
        (ports.single().obj())["targetPort"] = "management"
        manifests.problems().assert().anyMatch { it.contains("publishes the management port") }
    }

    @Test
    fun `the control directory does not state the boundary, and either statement there is reported`() {
        for (statement in listOf(STATEMENT, "COSEC_PIPELINE_IDENTITY_ONLYGATEWAYCANREACH")) {
            val stated = load()
            stated.container().env().add(mutableMapOf("name" to statement, "value" to "true"))
            stated.problems().assert().anyMatch { it.contains(statement) }
        }
    }

    @Test
    fun `an overlay states the boundary beside the check, and the dev overlay says first that it is unsafe on a shared cluster`() {
        // The check is a resource of k8s/control, which every overlay builds (the test above): an overlay that states the
        // boundary states it beside the check.
        val production = documents(repository.resolve("k8s/overlays/production/control-deployment-patch.yaml")).single()
        val env = production.at("spec", "template", "spec", "containers").arr().single().obj().env()
        env.single { it["name"] == STATEMENT }["value"].assert().isEqualTo("true")
        env.none { it["name"] == "COSEC_PIPELINE_IDENTITY_ONLYGATEWAYCANREACH" }.assert().isTrue()
        val dev = repository.resolve("k8s/overlays/dev/kustomization.yaml").readText()
        dev.lineSequence().first().assert().startsWith("# UNSAFE ON A SHARED CLUSTER.")
        dev.assert().contains(STATEMENT).contains("cosec-control-boundary-check")
        for (overlay in listOf("dev", "production")) {
            documents(repository.resolve("k8s/overlays/$overlay/kustomization.yaml")).single()["resources"].arr()
                .assert().describedAs("$overlay's resources").contains("../../control")
        }
    }

    @Test
    fun `a check that a policy admits, that leaves out a port, or that cannot find a pod that is not ready is reported`() {
        val admitted = load()
        admitted.checkLabels()["app.kubernetes.io/name"] = "cosec-gateway"
        admitted.problems().assert().anyMatch { it.contains("admits the boundary check") }

        val port = load()
        port.checkScript(port.checkScript().replace("8081", "8090"))
        port.problems().assert().anyMatch { it.contains("does not try port management") }

        val ready = load()
        ready.services.last().at("spec").obj()["publishNotReadyAddresses"] = false
        ready.problems().assert().anyMatch { it.contains("publishNotReadyAddresses") }

        val proxied = load()
        proxied.services.last().obj().remove("spec")
        proxied.problems().assert().anyMatch { it.contains("cosec-control-pods") }
    }

    @Test
    fun `probes on a port other than the management port are reported`() {
        val manifests = load()
        (manifests.container()["readinessProbe"].obj().getValue("httpGet").obj())["port"] = "http"
        manifests.problems().assert().anyMatch { it.contains("readinessProbe") }
    }

    @Test
    fun `every manifest of the directory is a resource of its kustomization, and every overlay builds it`() {
        val kustomization = documents(control.resolve("kustomization.yaml")).single()
        val resources = kustomization["resources"].arr().map { it.toString() }.toSet()
        val files = Files.list(control).use { paths ->
            paths.map { it.name }.filter { it.endsWith(".yaml") }.toList()
        }.toSet() - setOf("kustomization.yaml", "application.yaml")
        resources.assert().describedAs("the resources of k8s/control/kustomization.yaml").isEqualTo(files)
        files.filter { it.startsWith("networkpolicy-") }.assert().hasSize(3)
        for (overlay in listOf("dev", "production")) {
            val built = documents(repository.resolve("k8s/overlays/$overlay/kustomization.yaml")).single()
            built["resources"].arr().assert().describedAs("$overlay's resources").contains("../../control")
            built["configMapGenerator"].arr().map { it.obj()["name"] }
                .assert().describedAs("$overlay's ConfigMaps").contains("cosec-control-redis")
        }
    }

    @Test
    fun `no overlay removes what the statement rests on`() {
        val overlays = Files.walk(repository.resolve("k8s/overlays")).use { paths ->
            paths.filter { Files.isRegularFile(it) }.toList()
        }
        overlays.forEach { it.readText().assert().describedAs("$it").doesNotContain("\$patch: delete") }
        // The control host's account is its own: the gateways' Secret never reaches it.
        val patch = repository.resolve("k8s/overlays/production/control-deployment-patch.yaml").readText()
        patch.assert().contains("cosec-control-redis-credentials").doesNotContain("cosec-redis-secret")
    }

    @Test
    fun `the configuration does not move a port, and the files are where the image reads them`() {
        val config = documents(control.resolve("application.yaml")).single()
        config.at("server", "port").assert().describedAs("server.port").isNull()
        config.at("management", "server", "port").assert().describedAs("management.server.port").isNull()
        config.at("cosec", "control", "http", "service-port").assert().describedAs("service-port").isNull()
        val dockerfile = repository.resolve("platform/control-server/Dockerfile").readText()
        val home = "/opt/" + Regex("ARG APP_NAME=(\\S+)").find(dockerfile)!!.groupValues[1]
        val mounts = load().container()["volumeMounts"].arr().map { it.obj()["mountPath"].toString() }
        mounts.filter { it != "/tmp" }.assert().isNotEmpty().allMatch { it.startsWith("$home/") }
    }

    @Test
    fun `a release moves the control image with the gateway's`() {
        fun tag(file: String) = documents(repository.resolve(file)).single()["images"].arr().single().obj()["newTag"]
        tag("k8s/control/kustomization.yaml").assert().isEqualTo(tag("k8s/base/kustomization.yaml"))
    }

    private companion object {
        const val STATEMENT = "COSEC_PIPELINE_IDENTITY_ONLYTRUSTEDCALLERSCANREACH"
    }
}

/** The files of `k8s/control/`, parsed, with the ports the image serves; [problems] is what is wrong with them. */
private class ControlManifests(
    val deployment: Map<String, Any?>,
    val services: List<Map<String, Any?>>,
    val check: Map<String, Any?>,
    val policies: List<Map<String, Any?>>,
    private val gatewayLabels: Map<String, String>,
    private val image: Map<String, Int>
) {
    fun container(): MutableMap<String, Any?> =
        deployment.at("spec", "template", "spec", "containers").arr().single().obj()

    private fun podLabels() = deployment.at("spec", "template", "metadata", "labels").strings()

    private fun portNumbers() = container().ports().associate { it["name"].toString() to it["containerPort"] as Int }

    private fun selects(selector: Any?, labels: Map<String, String>): Boolean {
        val wanted = ((selector as? Map<*, *>)?.get("matchLabels") as? Map<*, *>)
            ?.entries?.associate { it.key.toString() to it.value.toString() }
        return !wanted.isNullOrEmpty() && labels.entries.containsAll(wanted.entries)
    }

    /** The container's name for a port given by name or number, or null when it names none. */
    private fun portOf(reference: Any?): String? {
        val byNumber = (reference as? Number)?.let { number ->
            portNumbers().entries.firstOrNull { it.value == number.toInt() }?.key
        }
        return byNumber ?: reference?.toString()?.takeIf { it in portNumbers() }
    }

    private fun name(manifest: Map<String, Any?>) = manifest.at("metadata", "name").toString()

    fun problems(): List<String> =
        containerProblems() + serviceProblems() + policyProblems() + portProblems() + checkProblems()

    fun checkLabels(): MutableMap<String, Any?> = check.at("spec", "template", "metadata", "labels").obj()

    private fun checkContainer() = check.at("spec", "template", "spec", "containers").arr().single().obj()

    fun checkScript(): String = checkContainer()["command"].arr().last().toString()

    fun checkScript(script: String) {
        @Suppress("UNCHECKED_CAST")
        val command = checkContainer()["command"] as MutableList<Any?>
        command[command.lastIndex] = script
    }

    /**
     * The Job that checks the boundary on the cluster: from a pod no policy admits, to every port of every pod, by the
     * addresses of a Service that publishes nothing and answers pods that are not ready (the host is not ready until the
     * platform document is installed).
     */
    private fun checkProblems(): List<String> = buildList {
        val labels = checkLabels().strings()
        if (policies.flatMap { sources(it) }.any { selects(it["podSelector"], labels) }) {
            add("a NetworkPolicy admits the boundary check's pods: it must come from a pod no policy admits")
        }
        val script = checkScript()
        for ((name, number) in portNumbers()) {
            if (!Regex(
                    "\\b$number\\b"
                ).containsMatchIn(script)
            ) {
                add("the boundary check does not try port $name ($number)")
            }
        }
        val headless = services.firstOrNull { name(it) == PODS }
        val resolved = headless != null && script.contains(PODS)
        if (!resolved) add("the boundary check does not find the pods by the Service $PODS")
        if (headless != null) {
            if (headless.at("spec", "clusterIP") != "None") add("Service $PODS is not headless: it would proxy")
            if (headless.at("spec", "publishNotReadyAddresses") != true) {
                add(
                    "Service $PODS does not set publishNotReadyAddresses: the check would not find a host that is not ready"
                )
            }
            if (headless.at("spec", "ports") != null) add("Service $PODS publishes a port")
        }
    }

    private fun containerProblems(): List<String> = buildList {
        for ((name, expected) in image) {
            val actual = portNumbers()[name]
            if (actual != expected) add("the container's port $name is $actual, the image serves $expected")
        }
        for (probe in listOf("startupProbe", "livenessProbe", "readinessProbe")) {
            val port = container()[probe].obj().getValue("httpGet").obj()["port"]
            if (port != "management") add("$probe is on port $port, the probes run on the management port")
        }
        for (statement in container().env().map { it["name"].toString() }.filter { it.startsWith(STATEMENT_PREFIX) }) {
            add("the Deployment of k8s/control states $statement: the overlay states it, beside the boundary check")
        }
    }

    private fun serviceProblems(): List<String> = buildList {
        for (service in services) {
            val selector = service.at("spec", "selector") as? Map<*, *>
            if (selector == null || !podLabels().entries.containsAll(selector.obj().strings().entries)) {
                add("Service ${name(service)} does not select the Deployment's pods")
            }
            for (port in (service.at("spec", "ports") as? List<*>).orEmpty()) {
                if (portOf(port.obj()["targetPort"]) == "management") {
                    add("Service ${name(service)} publishes the management port")
                }
            }
        }
    }

    /** Each policy on its own: what it selects, and that no rule admits every source or every port. */
    private fun policyProblems(): List<String> = buildList {
        for (policy in policies) {
            val name = name(policy)
            if (!selects(policy.at("spec", "podSelector"), podLabels())) {
                add("NetworkPolicy $name does not select the Deployment's pods")
            }
            if ("Ingress" !in policy.at("spec", "policyTypes").arr()) {
                add(
                    "NetworkPolicy $name does not restrict ingress"
                )
            }
            for (rule in policy.at("spec", "ingress").arr()) {
                val from = rule.obj()["from"]?.arr().orEmpty()
                if (from.isEmpty()) add("NetworkPolicy $name admits every source")
                if (rule.obj()["ports"]?.arr().orEmpty().isEmpty()) add("NetworkPolicy $name admits every port")
                if (from.any { !isNamedSource(it.obj()) }) {
                    add("NetworkPolicy $name has an empty selector or an address block as a source")
                }
            }
        }
    }

    private fun isNamedSource(source: Map<String, Any?>): Boolean {
        val selectors = listOf("namespaceSelector", "podSelector").filter { it in source }
        return "ipBlock" !in source && selectors.isNotEmpty() &&
            selectors.all { !source[it].obj()["matchLabels"]?.strings().isNullOrEmpty() }
    }

    private fun admittedPorts(policy: Map<String, Any?>): List<String> =
        policy.at("spec", "ingress").arr().flatMap { it.obj()["ports"]?.arr().orEmpty() }.map { port ->
            portOf(port.obj()["port"]) ?: "?${port.obj()["port"]}"
        }

    private fun sources(policy: Map<String, Any?>): List<Map<String, Any?>> =
        policy.at("spec", "ingress").arr().flatMap { it.obj()["from"]?.arr().orEmpty() }.map { it.obj() }

    /** One policy per port, and each admits the source its port is for. */
    private fun portProblems(): List<String> = buildList {
        for (port in image.keys) {
            val admitting = policies.filter { port in admittedPorts(it) }.map { name(it) }
            if (admitting.isEmpty()) add("port $port has no NetworkPolicy")
            if (admitting.size > 1) add("port $port is admitted by more than one rule: $admitting")
        }
        for (policy in policies) {
            val ports = admittedPorts(policy).toSet()
            ports.filter { it !in image }.forEach {
                add(
                    "NetworkPolicy ${name(policy)} admits a port the container does not name: $it"
                )
            }
            if (ports.size > 1) {
                add(
                    "NetworkPolicy ${name(policy)} covers more than one port: $ports (one policy per port)"
                )
            }
            ports.singleOrNull()?.let { addAll(sourceProblems(it, sources(policy))) }
        }
    }

    private fun sourceProblems(port: String, sources: List<Map<String, Any?>>): List<String> = buildList {
        val gatewayOnly = sources.all { selects(it["podSelector"], gatewayLabels) && !selects(it["podSelector"], podLabels()) }
        if (port == "http" && !gatewayOnly) add("the operators' port admits more than the gateway's pods")
        if (port == "service") {
            if (sources.any { "namespaceSelector" !in it || "podSelector" !in it }) {
                add("the service port admits a source that names no namespace or no pod")
            }
            if (sources.any { selects(it["podSelector"], gatewayLabels) }) {
                add("the service port admits the gateway's pods, which do not call it")
            }
        }
        if (port == "management" && sources.any { it["namespaceSelector"] == null }) {
            add("the management port admits pods of its own namespace, not a monitoring namespace")
        }
    }

    private companion object {
        const val STATEMENT_PREFIX = "COSEC_PIPELINE_IDENTITY_ONLY"
        const val PODS = "cosec-control-pods"
    }
}

@Suppress("UNCHECKED_CAST")
private fun Any?.obj(): MutableMap<String, Any?> = this as MutableMap<String, Any?>

@Suppress("UNCHECKED_CAST")
private fun Any?.arr(): List<Any?> = this as List<Any?>

private fun Any?.strings(): Map<String, String> = obj().mapValues { it.value.toString() }

private fun Map<String, Any?>.at(vararg path: String): Any? =
    path.fold(this as Any?) { node, key -> (node as? Map<*, *>)?.get(key) }

private fun Map<String, Any?>.ports(): List<MutableMap<String, Any?>> = this["ports"].arr().map { it.obj() }

private fun Map<String, Any?>.env(): MutableList<MutableMap<String, Any?>> {
    @Suppress("UNCHECKED_CAST")
    return this["env"] as MutableList<MutableMap<String, Any?>>
}
