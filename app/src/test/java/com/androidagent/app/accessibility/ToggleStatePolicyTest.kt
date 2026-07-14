package com.androidagent.app.accessibility

import com.androidagent.app.agent.UiNodeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class ToggleStatePolicyTest {
    @Test
    fun recognizesExplicitCheckableState() {
        assertEquals(ToggleState.OFF, ToggleStatePolicy.state(node(checked = false)))
        assertEquals(ToggleState.ON, ToggleStatePolicy.state(node(checked = true)))
    }

    @Test
    fun recognizesGenericToggleClassWithoutText() {
        assertEquals(ToggleState.OFF, ToggleStatePolicy.state(node(className = "android.widget.Switch")))
        assertEquals(ToggleState.UNKNOWN, ToggleStatePolicy.state(node(className = "android.widget.Button", text = "ambiguous")))
    }

    private fun node(
        className: String = "Button",
        text: String = "",
        checked: Boolean? = null,
    ) = UiNodeSnapshot(1, text, "", className, true, false, "0,0,100,100", checked = checked)
}
