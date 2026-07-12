package com.androidagent.app.agent

object ElementMatchPolicy {
    fun score(
        snapshot: UiNodeSnapshot,
        viewId: String,
        text: String,
        description: String,
        className: String,
        bounds: String,
        editable: Boolean,
        clickable: Boolean,
    ): Int {
        var score = 0
        if (className == snapshot.className) score += 2
        if (snapshot.viewId.isNotBlank() && viewId == snapshot.viewId) score += 6
        if (snapshot.text.isNotBlank() && text == snapshot.text) score += 5
        if (snapshot.description.isNotBlank() && description == snapshot.description) score += 5
        if (bounds == snapshot.bounds) score += 3
        if (editable == snapshot.editable) score += 1
        if (clickable == snapshot.clickable) score += 1
        return score
    }

    fun minimumScore(snapshot: UiNodeSnapshot): Int = when {
        snapshot.viewId.isNotBlank() -> 7
        snapshot.text.isNotBlank() || snapshot.description.isNotBlank() -> 6
        else -> 5
    }
}
