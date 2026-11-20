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

package me.ahoo.cosec.cocache

import kotlinx.coroutines.runBlocking
import me.ahoo.cosec.annotations.InternalCoSecApi
import me.ahoo.cosec.model.PrincipalId
import me.ahoo.cosec.model.TenantId
import me.ahoo.cosec.spi.id.RandomIdGenerator
import java.lang.management.ManagementFactory

/**
 * A measurement, not a check: what one "not revoked" entry of each revocation table (token ids, subjects) retains
 * locally in CoCache: heap per entry over 200,000 entries. It is a `main` and nothing JUnit discovers, so `check`
 * compiles it and never runs it; it runs for minutes and takes gigabytes of heap, so run it by hand, niced, and
 * never on a shared machine's full load. In-memory stores stand in for Redis: it needs no Redis.
 *
 * Run: `nice -n 15 ./gradlew :cocache:testClasses`, then `java -Xmx4g -cp <the cocache module's test runtime
 * classpath> me.ahoo.cosec.cocache.SaasScaleRevocationMeasurementKt` (it takes no argument and prints to the
 * console). There is no Gradle task for it: the classpath is what an IDE's run configuration for this file's
 * `main` uses. Its numbers are in `docs/reviews/2026-11/`: `saas-scale-data-plane.md` (the revocation lookups'
 * local copies). They are of the commit that report names; this file is kept compiling against `main`, not
 * re-measured.
 */

private fun used(): Long {
    repeat(4) {
        System.gc()
        Thread.sleep(150)
    }
    return ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
}

fun main() {
    val n = 200_000
    CoSecTestCaches().use { caches ->
        val tokens: RevokedTokenCache = caches.factory.createCache(
            CoSecCacheSpecs.revokedToken(caches.keyPrefix).toBuilder().policy { localMaximumSize = 1_000_000 }.build()
        )
        val subjects: RevokedSubjectCache = caches.factory.createCache(
            CoSecCacheSpecs.revokedSubject(caches.keyPrefix).toBuilder().policy { localMaximumSize = 1_000_000 }.build()
        )
        val reader = CoCacheRevocationReader(tokens, subjects)
        val jtis = List(n) { RandomIdGenerator.generate() }
        val principals = List(n) { "user-" + RandomIdGenerator.generate() }
        val tenant = TenantId("tenant-0123456789")
        runBlocking {
            repeat(1000) {
                reader.isRevoked("warm-$it")
                reader.subjectRevokedBefore(tenant, PrincipalId("warm-$it"))
            }
        }
        val base = used()
        runBlocking { jtis.forEach { reader.isRevoked(it) } }
        val afterTokens = used()
        runBlocking { principals.forEach { reader.subjectRevokedBefore(tenant, PrincipalId(it)) } }
        val afterSubjects = used()
        runBlocking { jtis.take(10).forEach { check(!reader.isRevoked(it)) } }
        println("jti length ${jtis[0].length}, principal length ${principals[0].length}")
        println(
            "token entry: ${(afterTokens - base) / n} B (ids held by the test, not counted: ${jtis[0].length} chars each)"
        )
        println("subject entry: ${(afterSubjects - afterTokens) / n} B")
    }
}
