package com.androidagent.app.agent

data class PredicateEvidence(val proven: Boolean, val details: List<String>)

private val videoActionTerms = listOf("点赞", "评论", "投币", "收藏", "分享", "like", "comment", "coin", "favorite", "share")
private val primaryVideoActionTerms = setOf("点赞", "投币", "收藏", "like", "coin", "favorite")

private fun visibleVideoActionTerms(observation: Observation): Set<String> = observation.nodes
    .asSequence()
    .filter { node ->
        !node.editable && !node.isInputMethod &&
            (node.clickable || observation.nodes.any { ancestor ->
                ancestor.clickable && ancestor.treePath.isNotBlank() && node.treePath.startsWith("${ancestor.treePath}/")
            })
    }
    .flatMap { node ->
        val label = "${node.text} ${node.description} ${node.viewId}"
        videoActionTerms.asSequence().filter { label.contains(it, true) }
    }
    .toSet()

object MilestoneEvaluator {
    fun evaluate(milestone: TaskMilestone, plan: TaskPlan, observation: Observation, targetPackage: String?): PredicateEvidence {
        val details = mutableListOf<String>()
        val results = milestone.successPredicates.map { predicate ->
            val value = when (predicate.valueRef) {
                "canonical_query" -> plan.canonicalQuery
                else -> predicate.literal
            }
            val proven = when (predicate.kind) {
                UiPredicateKind.PACKAGE_FOREGROUND -> targetPackage != null && observation.packageName == targetPackage
                UiPredicateKind.TEXT_PRESENT -> value != null && observation.nodes.any {
                    !it.editable && !it.isInputMethod &&
                        (it.packageName.isBlank() || it.packageName == observation.packageName) &&
                        (it.text.equals(value, true) || it.description.equals(value, true))
                }
                UiPredicateKind.EDITABLE_EQUALS -> value != null && observation.nodes.any {
                    it.editable && it.text == value
                }
                UiPredicateKind.IME_HIDDEN -> !observation.imeVisible
                UiPredicateKind.PROFILE_IDENTITY -> value != null && profileIdentityProven(value, observation)
                UiPredicateKind.CONTENT_CREATOR -> value != null && contentCreatorProven(value, observation)
                UiPredicateKind.TOGGLE_ON -> observation.nodes.any { node ->
                    val semanticLabel = "${node.text} ${node.description} ${node.viewId}".lowercase()
                    val matchesTarget = when (predicate.literal?.lowercase()) {
                        "like" -> semanticLabel.contains("点赞") || semanticLabel.contains("like")
                        null, "" -> false
                        else -> semanticLabel.contains(predicate.literal.orEmpty().lowercase())
                    }
                    matchesTarget && (node.checked == true || node.selected || node.description.contains("已点赞") || node.description.contains("取消点赞"))
                }
                UiPredicateKind.SEMANTIC_CLAIM -> false
            }
            details += "${predicate.kind}=${if (proven) "PROVEN" else "UNKNOWN"}: ${predicate.description}"
            proven
        }
        return PredicateEvidence(results.isNotEmpty() && results.all { it }, details)
    }

    fun evaluateHardPredicates(milestone: TaskMilestone, plan: TaskPlan, observation: Observation, targetPackage: String?): PredicateEvidence {
        val hardPredicates = milestone.successPredicates.filter {
                it.kind == UiPredicateKind.PACKAGE_FOREGROUND ||
                it.kind == UiPredicateKind.TEXT_PRESENT ||
                it.kind == UiPredicateKind.EDITABLE_EQUALS ||
                it.kind == UiPredicateKind.IME_HIDDEN ||
                it.kind == UiPredicateKind.PROFILE_IDENTITY ||
                it.kind == UiPredicateKind.CONTENT_CREATOR
        }
        if (hardPredicates.isEmpty()) return PredicateEvidence(true, listOf("No unresolved hard predicates"))
        return evaluate(milestone.copy(successPredicates = hardPredicates), plan, observation, targetPackage)
    }

    private fun profileIdentityProven(value: String, observation: Observation): Boolean {
        if (observation.imeVisible || observation.nodes.any { it.editable && it.focused && searchSemantics(it) }) return false
        val localTerms = listOf("粉丝", "关注", "投稿", "动态", "follower", "following", "avatar")
        val pageTerms = listOf("个人主页", "个人空间", "主页", "动态", "投稿", "space", "profile", "avatar_layout", "archive", "header")
        val screen = observation.nodes.joinToString(" ") { "${it.text} ${it.description} ${it.viewId}" }
        if (pageTerms.count { screen.contains(it, true) } < 2) return false
        return identityNodes(value, observation).any { identity ->
            val context = scopedIdentityContext(identity, observation)
            localTerms.any { context.contains(it, true) }
        }
    }

    private fun contentCreatorProven(value: String, observation: Observation): Boolean {
        if (observation.imeVisible || observation.nodes.any { it.editable && it.focused && searchSemantics(it) }) return false
        val creatorTerms = listOf("关注", "up主", "作者", "author", "avatar", "follow")
        val visibleActions = visibleVideoActionTerms(observation)
        if (visibleActions.size < 2 || visibleActions.none(primaryVideoActionTerms::contains)) return false
        return identityNodes(value, observation).any { identity ->
            val context = scopedIdentityContext(identity, observation)
            creatorTerms.any { context.contains(it, true) }
        }
    }

    private fun identityNodes(value: String, observation: Observation): List<UiNodeSnapshot> = observation.nodes.filter {
        !it.editable && !it.isInputMethod &&
            (it.packageName.isBlank() || it.packageName == observation.packageName) &&
            (it.text.equals(value, true) || it.description.equals(value, true))
    }

    private fun scopedIdentityContext(identity: UiNodeSnapshot, observation: Observation): String {
        val parts = identity.treePath.split('/').filter(String::isNotBlank)
        val ancestor = if (parts.size > 2) parts.dropLast(2).joinToString("/") else identity.treePath
        val nodes = observation.nodes.filter {
            ancestor.isNotBlank() && (it.treePath == ancestor || it.treePath.startsWith("$ancestor/")) &&
                !it.editable && !it.isInputMethod
        }
        if (nodes.isEmpty() || nodes.size > 64) return "${identity.text} ${identity.description} ${identity.viewId}"
        return nodes.joinToString(" ") { "${it.text} ${it.description} ${it.viewId} ${it.className}" }
    }

    private fun searchSemantics(node: UiNodeSnapshot): Boolean {
        val label = "${node.viewId} ${node.description} ${node.className}".lowercase()
        return label.contains("search") || label.contains("搜索")
    }
}

data class GuardResult(val action: AgentAction?, val rejection: String? = null)

class ToolGuard(
    private val plan: TaskPlan,
    private val targetPackage: String? = null,
) {
    private var submittedCanonicalQuery = false

    fun normalizeAndValidate(action: AgentAction, observation: Observation, milestone: TaskMilestone? = null): GuardResult {
        requiredWorkflowAction(observation, milestone)?.let { return GuardResult(it) }
        val lockedQuery = plan.canonicalQuery
        val normalized = when (action) {
            is AgentAction.InputText -> {
                if (lockedQuery != null) AgentAction.InputText(lockedQuery, action.nodeId) else action
            }
            is AgentAction.ClickText -> action
            is AgentAction.ClickNode -> action
            else -> action
        }
        if (normalized is AgentAction.ClickNode && lockedQuery != null) {
            val target = observation.nodes.firstOrNull { it.id == normalized.nodeId }
            if (target?.isInputMethod == true || isInputMethodPackage(target?.packageName)) {
                return GuardResult(null, "direct IME key clicks are forbidden; use input_text or submit_input")
            }
        }
        if (normalized is AgentAction.ClickNode && observation.nodes.none { it.id == normalized.nodeId }) {
            return GuardResult(null, "node is not part of the bound observation")
        }
        if (normalized is AgentAction.ClickText && normalized.text.isBlank()) {
            return GuardResult(null, "click_text requires non-blank visible text")
        }
        if (normalized is AgentAction.InputText) {
            val editableNodes = observation.nodes.filter { it.editable && it.enabled }
            if (normalized.nodeId != null && editableNodes.none { it.id == normalized.nodeId }) {
                return GuardResult(null, "input target is not an editable element in the bound observation")
            }
            if (normalized.nodeId == null && editableNodes.size != 1) {
                return GuardResult(null, "input_text requires nodeId when the editable target is ambiguous")
            }
        }
        if (normalized is AgentAction.SubmitInput && normalized.nodeId != null && observation.nodes.none { it.id == normalized.nodeId && it.editable }) {
            return GuardResult(null, "submit target is not an editable element in the bound observation")
        }
        if (normalized is AgentAction.EnsureToggle && observation.nodes.none { it.id == normalized.nodeId }) {
            return GuardResult(null, "toggle target is not part of the bound observation")
        }
        if (milestone?.kind == TaskMilestoneKind.FINAL_ACTION && milestone.successPredicates.any { it.kind == UiPredicateKind.TOGGLE_ON }) {
            val hardGate = MilestoneEvaluator.evaluateHardPredicates(milestone, plan, observation, observation.packageName)
            if (!hardGate.proven) {
                return GuardResult(null, "final interaction requires target-content proof: ${hardGate.details.joinToString(" | ")}")
            }
            when (normalized) {
                is AgentAction.ClickNode -> {
                    val target = observation.nodes.firstOrNull { it.id == normalized.nodeId }
                    if (target != null && isLikeControl(target)) {
                        return GuardResult(AgentAction.EnsureToggle(normalized.nodeId, true))
                    }
                }
                is AgentAction.ClickText -> {
                    if (normalized.text.contains("点赞") || normalized.text.contains("like", true)) {
                        val matches = observation.nodes.filter {
                            !it.editable && !it.isInputMethod && isLikeControl(it)
                        }
                        if (matches.size == 1) return GuardResult(AgentAction.EnsureToggle(matches.single().id, true))
                    }
                }
                is AgentAction.EnsureToggle -> {
                    val target = observation.nodes.firstOrNull { it.id == normalized.nodeId }
                    if (target != null && isLikeControl(target)) return GuardResult(normalized.copy(desired = true))
                }
                is AgentAction.Wait -> Unit
                else -> return GuardResult(null, "final toggle milestone only permits a bound like control")
            }
            if (normalized !is AgentAction.Wait) return GuardResult(null, "final action is not a uniquely bound like control")
        }
        if (observation.imeVisible && normalized is AgentAction.TapPoint) {
            return GuardResult(null, "visual point taps are forbidden while the IME is visible")
        }
        if (lockedQuery != null && milestone?.kind == TaskMilestoneKind.ENTER_QUERY) {
            when (normalized) {
                is AgentAction.ClickText -> if (!normalized.text.equals("搜索", true) && !normalized.text.equals("search", true)) {
                    return GuardResult(null, "only the app search entry may be opened before the locked query field is available")
                }
                is AgentAction.ClickNode -> {
                    val target = observation.nodes.firstOrNull { it.id == normalized.nodeId }
                    if (target == null || !searchEntrySemantics(target)) {
                        return GuardResult(null, "node is not the app search entry for the locked query")
                    }
                }
                is AgentAction.TapPoint, is AgentAction.Swipe, AgentAction.Back -> {
                    return GuardResult(null, "unbound navigation is forbidden before the locked query field is available")
                }
                else -> Unit
            }
        }
        if (lockedQuery != null && milestone?.kind == TaskMilestoneKind.SELECT_ENTITY) {
            val allowedFilters = setOf("用户", "UP主", "up主")
            when (normalized) {
                is AgentAction.ClickText -> {
                    if (normalized.text.equals(lockedQuery, true)) {
                        val matches = observation.nodes.filter {
                            !it.editable && !it.isInputMethod &&
                                (it.packageName.isBlank() || it.packageName == observation.packageName) &&
                                (it.text.equals(lockedQuery, true) || it.description.equals(lockedQuery, true))
                        }
                        if (matches.size != 1) return GuardResult(null, "locked-entity text must resolve to one non-editable result node")
                        return GuardResult(AgentAction.ClickNode(matches.single().id))
                    }
                    if (normalized.text !in allowedFilters) {
                        return GuardResult(null, "candidate text is unrelated to the locked entity '$lockedQuery'")
                    }
                }
                is AgentAction.ClickNode -> {
                    val target = observation.nodes.firstOrNull { it.id == normalized.nodeId }
                    val directLabel = "${target?.text.orEmpty()} ${target?.description.orEmpty()}".trim()
                    if (target == null || (!hasExactIdentityInLocalContext(target, lockedQuery, observation) && directLabel !in allowedFilters)) {
                        return GuardResult(null, "candidate node is unrelated to the locked entity '$lockedQuery'")
                    }
                }
                is AgentAction.TapPoint -> {
                    return GuardResult(null, "unbound visual points are forbidden while selecting the locked entity '$lockedQuery'")
                }
                else -> Unit
            }
        }
        if (milestone?.kind == TaskMilestoneKind.OPEN_CONTENT && normalized is AgentAction.TapPoint) {
            return GuardResult(null, "unbound visual points are forbidden while opening locked creator content")
        }
        if ((milestone?.kind == TaskMilestoneKind.OPEN_CONTENT && normalized is AgentAction.ClickNode) ||
            (milestone?.kind == TaskMilestoneKind.OPEN_CONTENT && normalized is AgentAction.ClickText)
        ) {
            val profileMilestone = plan.milestones.firstOrNull { it.kind == TaskMilestoneKind.SELECT_ENTITY }
            val profileGate = profileMilestone?.let {
                MilestoneEvaluator.evaluateHardPredicates(it, plan, observation, observation.packageName)
            }
            if (profileGate?.proven != true) {
                return GuardResult(null, "content selection requires the locked creator profile to be visibly proven")
            }
            val target = when (normalized) {
                is AgentAction.ClickNode -> observation.nodes.firstOrNull { it.id == normalized.nodeId }
                is AgentAction.ClickText -> observation.nodes.singleOrNull {
                    !it.editable && !it.isInputMethod && (it.text.equals(normalized.text, true) || it.description.equals(normalized.text, true))
                }
                else -> null
            }
            val context = target?.let { semanticContext(it, observation) }.orEmpty()
            val targetLabel = target?.let { "${it.text} ${it.description} ${it.viewId}" }.orEmpty().trim()
            val contentNavigation = setOf("投稿", "视频", "最新").any { targetLabel.equals(it, true) }
            val contentTerms = listOf(
                "视频", "投稿", "稿件", "播放", "更新", "刚刚", "分钟前", "小时前", "天前", "昨天",
                "title", "cover", "thumbnail", "archive", "video", "player", "duration", "bv",
            )
            if (!contentNavigation && contentTerms.none { context.contains(it, true) }) {
                return GuardResult(null, "selected node is not structurally bound to a creator content item")
            }
            if (!contentNavigation && plan.summary.contains("最新") && target != null && !isNewestContentCandidate(target, observation)) {
                return GuardResult(null, "selected content is not the newest visible item on the locked creator profile")
            }
        }
        if (milestone?.kind == TaskMilestoneKind.OPEN_CONTENT && plan.summary.contains("最新") && normalized is AgentAction.Swipe) {
            return GuardResult(null, "scrolling away from the top is forbidden while selecting the newest content")
        }
        if (milestone?.kind == TaskMilestoneKind.OPEN_CONTENT || milestone?.kind == TaskMilestoneKind.FINAL_ACTION) {
            val distractionTerms = listOf("云电视", "投屏", "热搜", "广告", "推广", "商城", "游戏中心", "首页", "home", "main_tab", "bottom_nav")
            val label = when (normalized) {
                is AgentAction.ClickText -> normalized.text
                is AgentAction.ClickNode -> observation.nodes.firstOrNull { it.id == normalized.nodeId }?.let {
                    "${it.text} ${it.description} ${it.viewId}"
                }.orEmpty()
                else -> ""
            }
            if (label.equals("搜索", true) || label.contains("search", true) || distractionTerms.any { label.contains(it, true) }) {
                return GuardResult(null, "navigation distraction does not advance the current content milestone")
            }
        }
        if (normalized is AgentAction.LaunchApp && normalized.packageName == observation.packageName) {
            return GuardResult(null, "target app is already foreground; redundant relaunch would reset progress")
        }
        if (normalized is AgentAction.Back && milestone?.kind in setOf(
                TaskMilestoneKind.SELECT_ENTITY,
                TaskMilestoneKind.OPEN_CONTENT,
                TaskMilestoneKind.FINAL_ACTION,
            )
        ) {
            return GuardResult(null, "Back is reserved for typed recovery and IME dismissal in this milestone")
        }
        if (normalized is AgentAction.Home) return GuardResult(null, "Home is not an allowed recovery strategy inside a locked app task")
        return GuardResult(normalized)
    }

    fun requiredWorkflowAction(observation: Observation, milestone: TaskMilestone? = null): AgentAction? {
        if (milestone?.kind == TaskMilestoneKind.LAUNCH_APP && !targetPackage.isNullOrBlank() && observation.packageName != targetPackage) {
            return AgentAction.LaunchApp(targetPackage)
        }
        val lockedQuery = plan.canonicalQuery ?: return null
        val keyboardVisible = keyboardVisible(observation)
        if (milestone?.kind == TaskMilestoneKind.OPEN_CONTENT ||
            (milestone?.kind == TaskMilestoneKind.FINAL_ACTION && milestone.successPredicates.any { it.kind == UiPredicateKind.TOGGLE_ON })
        ) {
            if (keyboardVisible) return AgentAction.Back
            if (milestone.kind == TaskMilestoneKind.OPEN_CONTENT && isVideoSurface(observation)) {
                val hardGate = MilestoneEvaluator.evaluateHardPredicates(milestone, plan, observation, observation.packageName)
                if (!hardGate.proven) return AgentAction.Back
            }
            return null
        }
        if (milestone?.kind !in setOf(TaskMilestoneKind.ENTER_QUERY, TaskMilestoneKind.SELECT_ENTITY)) return null
        val editableNodes = observation.nodes.filter { it.editable && it.enabled && !it.isInputMethod }
        val input = editableNodes.firstOrNull { searchSemantics(it) }
            ?: editableNodes.firstOrNull { it.focused }
            ?: editableNodes.singleOrNull()
            ?: return if (keyboardVisible) AgentAction.Back else null
        return if (input.text != lockedQuery) {
            AgentAction.InputText(lockedQuery, input.id)
        } else if (keyboardVisible) {
            if (submittedCanonicalQuery) AgentAction.Back else AgentAction.SubmitInput(input.id)
        } else null
    }

    fun recordDispatch(action: AgentAction) {
        when (action) {
            is AgentAction.InputText -> submittedCanonicalQuery = false
            is AgentAction.SubmitInput -> submittedCanonicalQuery = true
            else -> Unit
        }
    }

    private fun searchSemantics(node: UiNodeSnapshot): Boolean {
        val label = "${node.viewId} ${node.description} ${node.className}".lowercase()
        return label.contains("search") || label.contains("搜索")
    }

    private fun searchEntrySemantics(node: UiNodeSnapshot): Boolean {
        val label = "${node.text} ${node.description} ${node.viewId} ${node.className}".lowercase()
        val distractions = listOf("suggest", "recommend", "history", "result", "hot", "热搜", "推荐", "历史", "结果", "联想")
        return (label.contains("search") || label.contains("搜索")) && distractions.none(label::contains)
    }

    private fun keyboardVisible(observation: Observation): Boolean = observation.imeVisible || observation.nodes.any {
        it.isInputMethod || isInputMethodPackage(it.packageName) || it.className.contains("keyboard", true)
    }

    private fun isInputMethodPackage(packageName: String?): Boolean {
        val value = packageName.orEmpty().lowercase()
        return value.contains("inputmethod") || value.contains("keyboard") || value.endsWith(".ime") || value.contains(".ime.")
    }

    private fun semanticContext(target: UiNodeSnapshot, observation: Observation): String {
        val clickableRoot = observation.nodes.asSequence()
            .filter { node ->
                node.clickable && (node.treePath == target.treePath || target.treePath.startsWith("${node.treePath}/"))
            }
            .maxByOrNull { it.treePath.count { char -> char == '/' } }
        val rootPath = clickableRoot?.treePath ?: target.treePath
        val localNodes = observation.nodes.filter { node ->
            !node.editable && !node.isInputMethod && (node.packageName.isBlank() || node.packageName == observation.packageName) &&
                (node.treePath == rootPath || node.treePath.startsWith("$rootPath/"))
        }
        if (localNodes.size > 32) return "${target.text} ${target.description}"
        return localNodes.joinToString(" ") { "${it.text} ${it.description} ${it.viewId}" }
    }

    private fun hasExactIdentityInLocalContext(target: UiNodeSnapshot, value: String, observation: Observation): Boolean {
        val clickableRoot = observation.nodes.asSequence()
            .filter { node -> node.clickable && (node.treePath == target.treePath || target.treePath.startsWith("${node.treePath}/")) }
            .maxByOrNull { it.treePath.count { char -> char == '/' } }
        val rootPath = clickableRoot?.treePath ?: target.treePath
        val localNodes = observation.nodes.filter { node ->
            !node.editable && !node.isInputMethod &&
                (node.packageName.isBlank() || node.packageName == observation.packageName) &&
                (node.treePath == rootPath || node.treePath.startsWith("$rootPath/"))
        }
        if (localNodes.size > 32) return false
        return localNodes.any { it.text.equals(value, true) || it.description.equals(value, true) }
    }

    private fun isVideoSurface(observation: Observation): Boolean {
        return visibleVideoActionTerms(observation).size >= 2
    }

    private fun isLikeControl(node: UiNodeSnapshot): Boolean {
        val label = "${node.text} ${node.description} ${node.viewId}"
        return label.contains("点赞") || Regex("(^|[^a-z])(like|praise|thumb[_-]?up|zan)([^a-z]|$)", RegexOption.IGNORE_CASE).containsMatchIn(label)
    }

    private fun isNewestContentCandidate(target: UiNodeSnapshot, observation: Observation): Boolean {
        val structuralTerms = listOf(
            "视频", "播放", "更新", "刚刚", "分钟前", "小时前", "天前", "昨天",
            "title", "cover", "thumbnail", "archive", "video", "duration", "bv",
        )
        val candidates = observation.nodes.filter { node ->
            val directLabel = "${node.text} ${node.description}".trim()
            !node.editable && !node.isInputMethod && directLabel !in setOf("投稿", "视频", "最新") &&
                structuralTerms.any { semanticContext(node, observation).contains(it, true) }
        }
        if (candidates.isEmpty()) return false

        val targetContext = semanticContext(target, observation)
        if (targetContext.contains("置顶") || targetContext.contains("pinned", true)) return false
        val targetAge = relativeAgeMinutes(targetContext)
        val candidateAges = candidates.mapNotNull { relativeAgeMinutes(semanticContext(it, observation)) }
        return targetAge != null && candidateAges.isNotEmpty() && targetAge == candidateAges.minOrNull()
    }

    private fun relativeAgeMinutes(value: String): Long? {
        if (value.contains("刚刚") || value.contains("just now", true)) return 0
        if (value.contains("今天")) return 0
        if (value.contains("昨天")) return 24 * 60
        if (value.contains("前天")) return 2 * 24 * 60
        Regex("(\\d+)\\s*(分钟|小时|天|周|个月)前").find(value)?.let { match ->
            val amount = match.groupValues[1].toLongOrNull() ?: return null
            return when (match.groupValues[2]) {
                "分钟" -> amount
                "小时" -> amount * 60
                "天" -> amount * 24 * 60
                "周" -> amount * 7 * 24 * 60
                "个月" -> amount * 30 * 24 * 60
                else -> null
            }
        }

        val today = java.time.LocalDate.now()
        val fullDate = Regex("(20\\d{2})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?").find(value)?.let { match ->
            runCatching {
                java.time.LocalDate.of(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
            }.getOrNull()
        }
        val shortDate = if (fullDate == null) {
            Regex("(?<!\\d)(\\d{1,2})[-/.月](\\d{1,2})日?").find(value)?.let { match ->
                runCatching {
                    var date = java.time.LocalDate.of(today.year, match.groupValues[1].toInt(), match.groupValues[2].toInt())
                    if (date.isAfter(today.plusDays(1))) date = date.minusYears(1)
                    date
                }.getOrNull()
            }
        } else null
        val date = fullDate ?: shortDate ?: return null
        return java.time.temporal.ChronoUnit.DAYS.between(date, today).coerceAtLeast(0) * 24 * 60
    }
}

data class StepTrace(
    val milestoneId: String,
    val beforeId: String,
    val action: String,
    val afterId: String,
    val judgement: TransitionJudgement,
    val evidence: String,
)

class RunLedger(private var plan: TaskPlan) {
    var currentMilestoneIndex: Int = 0
        private set
    private val fingerprints = ArrayDeque<String>()
    private val attempts = mutableMapOf<String, Int>()
    private val finalInteractionAttempts = mutableMapOf<String, Int>()
    private val traces = mutableListOf<StepTrace>()
    private val evidence = linkedMapOf<String, String>()
    var noProgressCount: Int = 0
        private set

    val currentMilestone: TaskMilestone? get() = plan.milestones.getOrNull(currentMilestoneIndex)
    val complete: Boolean get() = currentMilestoneIndex >= plan.milestones.size

    fun replacePlan(newPlan: TaskPlan, completedMilestones: Int) {
        plan = newPlan
        currentMilestoneIndex = completedMilestones.coerceIn(0, newPlan.milestones.size)
        noProgressCount = 0
        fingerprints.clear()
        val retainedIds = newPlan.milestones.take(currentMilestoneIndex).mapTo(mutableSetOf()) { it.id }
        evidence.keys.retainAll(retainedIds)
    }

    fun observe(observation: Observation) {
        val fingerprint = observation.stateFingerprint()
        if (fingerprints.lastOrNull() == fingerprint) return
        fingerprints.addLast(fingerprint)
        while (fingerprints.size > 16) fingerprints.removeFirst()
    }

    fun blockRepeated(action: AgentAction, observation: Observation): String? {
        val milestone = currentMilestone ?: return null
        val finalInteraction = milestone.kind == TaskMilestoneKind.FINAL_ACTION && action is AgentAction.EnsureToggle
        if (finalInteraction) {
            val finalCount = finalInteractionAttempts.getOrDefault(milestone.id, 0)
            if (finalCount >= 1) return "final interaction already attempted; unsafe to toggle again without proof"
        }
        val key = "${milestone.id}|${observation.stateFingerprint()}|${semanticAction(action, observation)}"
        val count = attempts.getOrDefault(key, 0)
        if (count >= 2) return "strategy exhausted for the same milestone and screen"
        attempts[key] = count + 1
        return null
    }

    fun recordDispatch(action: AgentAction) {
        val milestone = currentMilestone ?: return
        val finalInteraction = milestone.kind == TaskMilestoneKind.FINAL_ACTION && action is AgentAction.EnsureToggle
        if (finalInteraction) finalInteractionAttempts[milestone.id] = finalInteractionAttempts.getOrDefault(milestone.id, 0) + 1
    }

    fun record(trace: StepTrace) {
        traces += trace
        if (trace.judgement == TransitionJudgement.NO_PROGRESS) noProgressCount += 1 else noProgressCount = 0
    }

    fun advance(evidence: String): String {
        val completed = currentMilestone?.id ?: "none"
        this.evidence[completed] = evidence
        currentMilestoneIndex += 1
        noProgressCount = 0
        return "$completed proven: $evidence"
    }

    fun cyclePeriod(): Int? {
        val trail = fingerprints.toList()
        for (period in 1..4) {
            if (trail.size >= period * 2 && trail.takeLast(period) == trail.dropLast(period).takeLast(period)) return period
        }
        return null
    }

    fun recentFailureContext(): String = traces.takeLast(8).joinToString("\n") {
        "${it.milestoneId}: ${it.action} -> ${it.judgement} (${it.evidence})"
    }

    fun planText(): String = plan.compactText(currentMilestoneIndex)

    fun evidenceSummary(): String = if (evidence.isEmpty()) {
        "No milestone evidence recorded"
    } else {
        evidence.entries.joinToString("\n") { (id, proof) -> "$id: $proof" }
    }

    private fun semanticAction(action: AgentAction, observation: Observation): String = when (action) {
        is AgentAction.ClickNode -> observation.nodes.firstOrNull { it.id == action.nodeId }?.let {
            "tap:${it.stableKey}:${it.viewId}:${it.text.lowercase()}:${it.description.lowercase()}"
        } ?: "tap:missing"
        is AgentAction.ClickText -> "tap_text:${action.text.lowercase()}"
        is AgentAction.TapPoint -> "tap_point:${action.x / 25}:${action.y / 25}"
        is AgentAction.InputText -> "set_text:${action.nodeId}:${action.text.lowercase()}"
        is AgentAction.SubmitInput -> "submit:${action.nodeId}"
        is AgentAction.EnsureToggle -> "ensure_toggle:${action.nodeId}:${action.desired}"
        is AgentAction.LaunchApp -> "launch:${action.packageName}"
        is AgentAction.Swipe -> "scroll:${action.direction}"
        AgentAction.Back -> "back"
        AgentAction.Home -> "home"
        else -> action::class.simpleName.orEmpty()
    }
}
