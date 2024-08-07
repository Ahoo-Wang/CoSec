package me.ahoo.cosec.policy

import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.ahoo.cosec.api.policy.Policy
import me.ahoo.cosec.authorization.PolicyRepository
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono

class LocalPolicyInitializerTest {
    private val localPolicyLoader = LocalPolicyLoader(
        setOf(
            "classpath:cosec-policy/*-policy.json"
        )
    )

    @Test
    fun init() {
        val mockPolicyRepository = mockk<PolicyRepository> {
            every { getPolicies(any()) } returns Mono.empty()
            every { setPolicy(any()) } returns Mono.empty()
        }
        val localPolicyInitializer = LocalPolicyInitializer(localPolicyLoader, mockPolicyRepository, false)
        localPolicyInitializer.init()
        verify {
            mockPolicyRepository.setPolicy(any())
        }
    }

    @Test
    fun initForceRefresh() {
        val mockPolicyRepository = mockk<PolicyRepository> {
            every { getPolicies(any()) } returns Mono.empty()
            every { setPolicy(any()) } returns Mono.empty()
        }
        val localPolicyInitializer = LocalPolicyInitializer(localPolicyLoader, mockPolicyRepository, true)
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
        val localPolicyInitializer = LocalPolicyInitializer(localPolicyLoader, mockPolicyRepository, false)
        localPolicyInitializer.init()
        verify {
            mockPolicyRepository.setPolicy(any())
        }
    }

    @Test
    fun initIfExists() {
        val mockPolicyRepository = mockk<PolicyRepository> {
            every { getPolicies(any()) } returns listOf(localPolicyLoader.policies.first()).toMono()
        }
        val localPolicyInitializer = LocalPolicyInitializer(localPolicyLoader, mockPolicyRepository, false)
        localPolicyInitializer.init()
        verify {
            mockPolicyRepository.getPolicies(any())
        }
    }

    @Test
    fun initIfError() {
        val mockPolicyRepository = mockk<PolicyRepository> {
            every { getPolicies(any()) } returns Mono.empty()
            every { setPolicy(any()) } returns RuntimeException().toMono()
        }
        val localPolicyInitializer = LocalPolicyInitializer(localPolicyLoader, mockPolicyRepository, false)
        Assertions.assertThrows(RuntimeException::class.java) {
            localPolicyInitializer.init()
        }
        verify {
            mockPolicyRepository.getPolicies(any())
            mockPolicyRepository.setPolicy(any())
        }
        confirmVerified(mockPolicyRepository)
    }
}
