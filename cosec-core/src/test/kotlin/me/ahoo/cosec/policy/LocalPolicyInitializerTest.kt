package me.ahoo.cosec.policy

import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.authorization.PolicyRepository
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

class LocalPolicyInitializerTest {
    private val localPolicyRepository = LocalPolicyLoader(
        setOf(
            "classpath:build-in"
        )
    )

    @Test
    fun init() {
        val mockPolicyRepository = mockk<PolicyRepository> {
            every { getPolicies(any()) } returns Mono.empty()
            every { setPolicy(any()) } returns Mono.empty()
        }
        val localPolicyInitializer = LocalPolicyInitializer(localPolicyRepository, mockPolicyRepository)
        localPolicyInitializer.init()
        verify {
            mockPolicyRepository.setPolicy(any())
        }
    }

    @Test
    fun initIfEmpty() {
        val mockPolicyRepository = mockk<PolicyRepository> {
            every { getPolicies(any()) } returns listOf<Policy>().toMono()
            every { setPolicy(any()) } returns Mono.empty()
        }
        val localPolicyInitializer = LocalPolicyInitializer(localPolicyRepository, mockPolicyRepository)
        localPolicyInitializer.init()
        verify {
            mockPolicyRepository.setPolicy(any())
        }
    }

    @Test
    fun initIfExists() {
        val mockPolicyRepository = mockk<PolicyRepository> {
            every { getPolicies(any()) } returns listOf(localPolicyRepository.policies.first()).toMono()
            every { setPolicy(any()) } returns Mono.empty()
        }
        val localPolicyInitializer = LocalPolicyInitializer(localPolicyRepository, mockPolicyRepository)
        localPolicyInitializer.init()
        verify {
            mockPolicyRepository.setPolicy(any()) wasNot Called
        }
    }

    @Test
    fun initIfError() {
        val mockPolicyRepository = mockk<PolicyRepository> {
            every { getPolicies(any()) } returns Mono.empty()
            every { setPolicy(any()) } returns RuntimeException().toMono()
        }
        val localPolicyInitializer = LocalPolicyInitializer(localPolicyRepository, mockPolicyRepository)
        localPolicyInitializer.init()
        verify {
            mockPolicyRepository.setPolicy(any()) wasNot Called
        }
    }
}
