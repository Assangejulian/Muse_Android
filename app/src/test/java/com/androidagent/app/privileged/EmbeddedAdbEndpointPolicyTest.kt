package com.androidagent.app.privileged

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmbeddedAdbEndpointPolicyTest {
    @Test
    fun usesSingleDiscoveredPairingEndpoint() {
        assertEquals(
            AdbEndpoint("192.168.1.8", 37123),
            EmbeddedAdbEndpointPolicy.pairEndpoint(
                listOf(AdbEndpoint("192.168.1.8", 37123)),
                manualHost = null,
                manualPort = null,
                localWifiHost = null,
            ),
        )
    }

    @Test
    fun manualPortUsesMatchingDiscoveryOrExplicitWlanHost() {
        val endpoints = listOf(AdbEndpoint("192.168.1.8", 37123))
        assertEquals(
            endpoints.first(),
            EmbeddedAdbEndpointPolicy.pairEndpoint(endpoints, null, 37123, "172.19.0.1"),
        )
        assertEquals(
            AdbEndpoint("172.19.0.1", 37123),
            EmbeddedAdbEndpointPolicy.pairEndpoint(endpoints, "172.19.0.1", 37123, null),
        )
        assertEquals(
            AdbEndpoint("172.19.0.1", 40199),
            EmbeddedAdbEndpointPolicy.pairEndpoint(endpoints, "172.19.0.1", 40199, null),
        )
        assertNull(EmbeddedAdbEndpointPolicy.pairEndpoint(endpoints, "172.19.0.1", 70_000, null))
    }

    @Test
    fun fallsBackToCurrentWlanAddressNeverLoopback() {
        assertEquals(
            AdbEndpoint("172.19.0.1", 40199),
            EmbeddedAdbEndpointPolicy.pairEndpoint(emptyList(), null, 40199, "172.19.0.1"),
        )
        assertNull(EmbeddedAdbEndpointPolicy.pairEndpoint(emptyList(), null, 40199, null))
    }

    @Test
    fun manualConnectionPortUsesSeparateEndpoint() {
        assertEquals(
            AdbEndpoint("172.19.0.1", 40931),
            EmbeddedAdbEndpointPolicy.connectEndpoint(emptyList(), "172.19.0.1", 40931, null),
        )
    }

    @Test
    fun ambiguousDiscoveryRequiresManualDetails() {
        assertNull(
            EmbeddedAdbEndpointPolicy.pairEndpoint(
                listOf(AdbEndpoint("127.0.0.1", 37123), AdbEndpoint("127.0.0.1", 37124)),
                manualHost = null,
                manualPort = null,
                localWifiHost = null,
            ),
        )
    }
}
