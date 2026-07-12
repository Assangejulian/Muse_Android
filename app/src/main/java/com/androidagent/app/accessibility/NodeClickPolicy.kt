package com.androidagent.app.accessibility

object NodeClickPolicy {
    fun isSafeBounds(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean {
        if (screenWidth <= 0 || screenHeight <= 0) return false
        if (left < 0 || top < 0 || right > screenWidth || bottom > screenHeight) return false
        if (right <= left || bottom <= top) return false
        val centerY = (top + bottom) / 2
        val protectedTop = (screenHeight * 0.03f).toInt()
        val protectedBottom = (screenHeight * 0.97f).toInt()
        return centerY in protectedTop..protectedBottom
    }
}
