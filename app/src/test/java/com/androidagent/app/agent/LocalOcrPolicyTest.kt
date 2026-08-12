package com.androidagent.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalOcrPolicyTest {
    @Test
    fun labeledSettingsPageDoesNotNeedOcr() {
        val nodes = (1..12).map { id ->
            UiNodeSnapshot(
                id = id,
                text = "Item $id",
                description = "",
                className = "android.widget.TextView",
                clickable = true,
                editable = false,
                bounds = "0,${id * 40},200,${id * 40 + 36}",
            )
        }
        assertFalse(LocalOcrPolicy.shouldEnrich(Observation("settings.app", nodes)))
    }

    @Test
    fun blankClickableCustomViewsNeedOcrEvenWhenNodeCountIsHigh() {
        val nodes = (1..12).map { id ->
            UiNodeSnapshot(
                id = id,
                text = "",
                description = "",
                className = "android.view.View",
                clickable = true,
                editable = false,
                bounds = "0,${id * 40},80,${id * 40 + 36}",
            )
        }
        assertTrue(LocalOcrPolicy.shouldEnrich(Observation("video.app", nodes)))
    }

    @Test
    fun sparseTextWithoutBlankActionablesStillNeedsOcr() {
        val nodes = listOf(
            UiNodeSnapshot(1, "Hi", "", "android.widget.TextView", false, false, "0,0,40,20"),
        )
        assertTrue(LocalOcrPolicy.shouldEnrich(Observation("canvas.app", nodes)))
    }
}
