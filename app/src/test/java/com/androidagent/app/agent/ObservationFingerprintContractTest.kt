package com.androidagent.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationFingerprintContractTest {
    @Test
    fun completenessAndPrivacyFlagsArePartOfObservationIdentity() {
        val complete = observation()
        assertNotEquals(complete.observationId, complete.copy(isComplete = false).observationId)
        assertNotEquals(complete.observationId, complete.copy(privacyFiltered = true).observationId)
    }

    @Test
    fun windowTopologyStillChangesObservationIdentity() {
        val first = observation().copy(windowIds = setOf(4), windowPackages = mapOf(4 to "primary.app"))
        val second = observation().copy(windowIds = setOf(5), windowPackages = mapOf(5 to "primary.app"))
        assertNotEquals(first.observationId, second.observationId)
    }

    @Test
    fun dynamicClockTextNormalizationIsPreserved() {
        val first = observation("12:34")
        val second = observation("12:35")
        assertEquals(first.observationId, second.observationId)
    }

    @Test
    fun diagnosticCollectorCountersDoNotCreateFalseScreenChanges() {
        val first = observation().copy(collectionIssues = "unresolved_children=1")
        val second = observation().copy(collectionIssues = "unresolved_children=3")

        assertEquals(first.observationId, second.observationId)
    }

    @Test
    fun localOcrDoesNotInvalidateAccessibilityBoundObservationIdentity() {
        val first = observation().copy(ocrText = "Loading")
        val second = observation().copy(ocrText = "Ready")

        assertEquals(first.observationId, second.observationId)
    }

    @Test
    fun boundsAndFocusFlickerAreNotStructuralStale() {
        val first = observation()
        val second = observation().copy(
            nodes = first.nodes.map { it.copy(bounds = "8,12,120,56", focused = true) },
        )
        assertNotEquals(first.observationId, second.observationId)
        assertEquals(first.structureFingerprint(), second.structureFingerprint())
        assertFalse(ObservationDispatchPolicy.isStale(first, second))
    }

    @Test
    fun packageOrWindowChangeIsStructuralStale() {
        val first = observation()
        val otherPackage = first.copy(packageName = "other.app")
        val otherWindow = first.copy(windowIds = setOf(9), windowPackages = mapOf(9 to "primary.app"))
        assertTrue(ObservationDispatchPolicy.isStale(first, otherPackage))
        assertTrue(ObservationDispatchPolicy.isStale(first, otherWindow))
    }

    private fun observation(text: String = "Ready") = Observation(
        packageName = "primary.app",
        nodes = listOf(
            UiNodeSnapshot(
                id = 1,
                text = text,
                description = "",
                className = "android.widget.TextView",
                clickable = false,
                editable = false,
                bounds = "0,0,100,40",
                viewId = "primary:id/status",
                treePath = listOf(0, 0),
                packageName = "primary.app",
                windowId = 4,
            ),
        ),
        windowIds = setOf(4),
        windowPackages = mapOf(4 to "primary.app"),
    )
}
