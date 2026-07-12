package com.androidagent.app.accessibility

import com.androidagent.app.agent.UiNodeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class ToggleStatePolicyTest {
    @Test
    fun recognizesLikeWithCountAsOff() {
        assertEquals(
            ToggleState.OFF,
            ToggleStatePolicy.state(node(description = "点赞 1.8万", viewId = "tv.danmaku.bili:id/like")),
        )
    }

    @Test
    fun recognizesExplicitLikedLabelsAsOn() {
        assertEquals(ToggleState.ON, ToggleStatePolicy.state(node(description = "取消点赞 1.8万")))
        assertEquals(ToggleState.ON, ToggleStatePolicy.state(node(text = "已点赞")))
    }

    @Test
    fun rejectsAmbiguousLikeRelatedContent() {
        assertEquals(ToggleState.UNKNOWN, ToggleStatePolicy.state(node(text = "点赞列表")))
        assertEquals(ToggleState.UNKNOWN, ToggleStatePolicy.state(node(text = "猜你喜欢")))
    }

    @Test
    fun preservesExplicitCheckableState() {
        assertEquals(ToggleState.OFF, ToggleStatePolicy.state(node(text = "Switch", checked = false)))
        assertEquals(ToggleState.ON, ToggleStatePolicy.state(node(text = "Switch", checked = true)))
    }

    private fun node(
        text: String = "",
        description: String = "",
        viewId: String = "",
        checked: Boolean? = null,
    ) = UiNodeSnapshot(
        id = 1,
        text = text,
        description = description,
        className = "Button",
        clickable = true,
        editable = false,
        bounds = "0,0,100,100",
        viewId = viewId,
        checked = checked,
    )
}
