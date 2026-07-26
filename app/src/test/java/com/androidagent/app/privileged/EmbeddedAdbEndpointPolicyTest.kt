package com.androidagent.app.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmbeddedAdbEndpointPolicyTest {
    @Test
    fun usesSingleDiscoveredPairingEndpoint() {
        assertEquals(
            AdbEndpoint("192.168.1.8", 37123),
            EmbeddedAdbEndpointPolicy.pairEndpoint(listOf(AdbEndpoint("192.168.1.8", 37123)), null),
        )
    }

    @Test
    fun manualPortUsesMatchingHostOrDeviceLoopback() {
        val endpoints = listOf(AdbEndpoint("192.168.1.8", 37123))
        assertEquals(
            endpoints.first(),
            EmbeddedAdbEndpointPolicy.pairEndpoint(endpoints, 37123),
        )
        assertEquals(
            AdbEndpoint("127.0.0.1", 38999),
            EmbeddedAdbEndpointPolicy.pairEndpoint(endpoints, 38999),
        )
        assertNull(EmbeddedAdbEndpointPolicy.pairEndpoint(endpoints, 70_000))
    }

    @Test
    fun ambiguousDiscoveryRequiresManualPort() {
        assertNull(
            EmbeddedAdbEndpointPolicy.pairEndpoint(
                listOf(AdbEndpoint("127.0.0.1", 37123), AdbEndpoint("127.0.0.1", 37124)),
                null,
            ),
        )
    }
}
