package com.androidagent.app.accessibility

/**
 * Collect on-screen nodes even when another window (including Muse's own
 * overlay) reports them as not visible-to-user. Off-screen recycled nodes stay
 * out of the snapshot.
 */
object ObservationNodePolicy {
    fun shouldCollect(
        visibleToUser: Boolean,
        onScreen: Boolean,
        clickable: Boolean,
        editable: Boolean,
        scrollable: Boolean,
        checkable: Boolean,
        selected: Boolean,
        hasText: Boolean,
        hasDescription: Boolean,
        hasViewId: Boolean,
    ): Boolean {
        val meaningful = clickable || editable || scrollable || checkable || selected ||
            hasText || hasDescription || hasViewId
        return meaningful && (visibleToUser || onScreen)
    }

    fun isOnScreen(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean {
        if (screenWidth <= 0 || screenHeight <= 0) return false
        if (right <= left || bottom <= top) return false
        return right > 0 && bottom > 0 && left < screenWidth && top < screenHeight
    }
}
