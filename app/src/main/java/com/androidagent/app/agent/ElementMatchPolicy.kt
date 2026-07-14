package com.androidagent.app.agent

/** Stable, ordered matching rules for re-locating a node after a tree refresh. */
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
    ): Int = score(snapshot, snapshot.packageName, viewId, text, description, className, bounds, editable, clickable)

    fun score(
        snapshot: UiNodeSnapshot,
        viewId: String,
        text: String,
        description: String,
        className: String,
        bounds: String,
        editable: Boolean,
        clickable: Boolean,
        treePath: List<Int>?,
    ): Int = score(snapshot, snapshot.packageName, viewId, text, description, className, bounds, editable, clickable, treePath)

    fun score(
        snapshot: UiNodeSnapshot,
        packageName: String,
        viewId: String,
        text: String,
        description: String,
        className: String,
        bounds: String,
        editable: Boolean,
        clickable: Boolean,
        treePath: List<Int>? = null,
    ): Int {
        if (snapshot.packageName.isNotBlank() && packageName.isNotBlank() && snapshot.packageName != packageName) return 0
        var score = 0
        if (snapshot.packageName.isNotBlank() && snapshot.packageName == packageName) score += 8
        if (snapshot.viewId.isNotBlank() && snapshot.viewId == viewId) score += 100
        if (snapshot.text.isNotBlank() && snapshot.text == text && snapshot.className == className) score += 60
        if (snapshot.description.isNotBlank() && snapshot.description == description && snapshot.className == className) score += 55
        if (snapshot.className.isNotBlank() && snapshot.className == className) score += 8
        if (snapshot.treePath != null && snapshot.treePath == treePath) score += 25
        if (snapshot.bounds == bounds) score += 12
        if (editable == snapshot.editable) score += 2
        if (clickable == snapshot.clickable) score += 2
        return score
    }

    fun minimumScore(snapshot: UiNodeSnapshot): Int = when {
        snapshot.viewId.isNotBlank() -> 100
        snapshot.text.isNotBlank() || snapshot.description.isNotBlank() -> 60
        snapshot.treePath != null -> 25
        else -> 12
    }

    fun score(snapshot: UiNodeSnapshot, selector: ElementSelector): Int = score(
        snapshot = snapshot,
        packageName = selector.packageName.orEmpty(),
        viewId = selector.viewIdResourceName.orEmpty(),
        text = selector.text.orEmpty(),
        description = selector.description.orEmpty(),
        className = selector.className.orEmpty(),
        bounds = selector.bounds.orEmpty(),
        editable = snapshot.editable,
        clickable = snapshot.clickable,
        treePath = selector.treePath,
    )
}
