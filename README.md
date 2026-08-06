# Muse Android Agent 0.12.0

A private, sideloaded Android 11–17 automation agent. It observes the active UI through accessibility and optional vision, asks the selected model for one constrained action, validates that action locally, and independently checks the result. Workflow decomposition is model-first: the Actor owns routing (including launch timing); Shizuku is the preferred control terminal; the runtime does not inject app-specific search/NLP recipes or hard-coded first-step launches.

## MVP capabilities

- Accessibility node observation
- DeepSeek, Qwen, or MiMo planning through OpenAI-compatible APIs
- Default DeepSeek model preset: `deepseek-v4-pro`; runtime thinking is disabled for a fast initial plan, and the Actor retains screen-level autonomy
- Native `tools` / `tool_calls` planning for DeepSeek and Qwen, with a cached compatibility fallback
- One strictly validated action per model response, with unknown action fields ignored
- Target package allowlist
- Sensitive-page recovery instead of immediate hard stop
- Text and node clicks
- Four-direction swipes
- Focused text input
- Back, app launch, exact text replacement, submit, wait, scroll, and idempotent toggle tools
- Encrypted local API key storage
- Manual stop, cancellable HTTP calls, gesture/screenshot hard timeouts, a twenty-minute deadline, and a bounded 120-tool run budget
- Chinese chat workspace with a configuration drawer
- Persistent conversations with create, pin, and delete actions
- Launchable app catalog exposed through `/list`
- Automatic target app selection from the installed app catalog
- GitHub Release update checks on app launch
- User-confirmed APK download and installation
- Live node-ID clicking with clickable-parent and safe center-tap fallbacks
- Full-screen animated execution field using an accessibility overlay
- Bottom task bar with an always-available stop action and at most two sliding progress-summary lines
- Final-action completion hints and repeated-toggle protection
- Observation filtering that prevents the agent from acting on its own overlay controls
- Natural conversation and device-action intent routing
- Deterministic Manager fallback plan when model planning fails
- `/chat` and `/run` overrides for ambiguous messages
- Bundled on-device Chinese OCR fallback that automatically enriches text-sparse accessibility observations
- Configurable OpenAI-compatible base URL and model name
- DeepSeek, Qwen, and MiMo configuration presets
- Conservative conversation context budgeting with space reserved for the current request
- App-private SQLite conversation storage with automatic legacy migration
- Explicit `/schedule <triggerAtMillis>|<goal>` scheduling through Android WorkManager (OCR time parsing remains available to callers)
- Strict completion verification and repeated-action recovery
- Optional screenshot planning with a separate OpenAI-compatible vision model
- In-app APK download progress and cancellation
- Stateful execution harness with goal contracts, milestones, screen fingerprints, transition checks, and loop recovery
- Manager/Actor/Critic/Verifier runtime with deterministic Pre/Post Tool hooks and a Stop Gate
- Observation-bound stable element matching and exact text readback validation
- Set-of-Mark screenshots and guarded normalized visual point taps for inaccessible controls
- Stale-observation rejection before state-dependent actions
- Visual before/after Critic checks with hard deterministic predicate gates
- Typed milestone contracts with deterministic local predicates and IME submission verification
- Input-method windows excluded from Actor observations and Set-of-Mark screenshots
- Generic task plans without app-specific creator, profile, or latest-video routing in the core runtime
- Empty task-recipe registry (optional hook only); no app-specific search/NLP recipes hijack planning
- Manager/Actor prompts stay app-agnostic: typed INPUT only for user-supplied values; lists/feeds/ordinals are navigation
- Pre-tool target proof plus idempotent state-transition controls
- App-private SQLite run traces available through `/trace`
- A run console showing the current phase, action, progress, outcome, and full trace
- Model-visible node prioritization, SHA-256 screen fingerprints, adaptive settle polling, and cycle recovery
- Privacy preflight before every model call, PII redaction, strict package locking, and screenshot binding checks
- Neutral app selection that exposes only the goal and installed app catalog until a target package is locked
- Explicit opt-in for screenshot sharing; vision is never enabled merely because a Qwen key exists
- Race-safe run state updates across UI, WorkManager, and accessibility callbacks
- External Shizuku 13.1.5 UserService as the primary model control terminal
- Harness-controlled model terminal tool that is advertised only while Shizuku is connected
- Automatic accessibility fallback when the privileged terminal is unavailable
- Accessibility observation, verification, and action fallback when Shizuku cannot perform a step
- Privileged foreground-app detection plus launch, tap, swipe, Back, Home, and Enter fallbacks
- Explicit user-only `/shell <command>` console with bounded command length, timeout, and output

User-started runs are protected by a foreground service. Exact-alarm special access remains intentionally deferred; scheduled work uses WorkManager and may run later than the parsed time under Android battery optimization.

## Shizuku control terminal

Muse intentionally does not bundle an ADB client. Install and start the separate Shizuku app, then grant Muse access. Shizuku runs Muse's bounded UserService with Android `shell` identity, or root identity only when the user's own Shizuku environment supplies it.

When Shizuku is live, the Harness exposes a bounded `terminal` action and directs the Actor to use it first for deterministic inspection and manipulation. Terminal output is returned to the planning loop but does not bypass milestone predicates or the Stop Gate. If Shizuku drops, the action disappears from the model schema and execution falls back to accessibility. Commands are limited to 16,000 characters and 30 seconds; trace files store only command length and a short digest.

## Build

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat test
.\gradlew.bat lint
```

## First run

1. Install the debug APK.
2. Open Muse.
3. Install and start the separate **Shizuku** app using its documented setup method.
4. In Muse's **Shizuku 控制终端** section, grant access and connect.
5. Tap **Accessibility** and enable **Muse Control** for observation, verification, overlays, and fallback actions.
6. Select DeepSeek or Qwen and enter that provider's API key. The Qwen text preset uses `qwen3.6-flash`; optional vision uses `qwen3-vl-flash`.
7. Optionally set a default target package. Leave it blank for automatic app selection.
8. Enter a narrow, low-risk task in the chat input and tap **发送**.
9. Enter `/list` to inspect the launchable app catalog, or `/shell <command>` to run an explicit Shizuku command.
10. To schedule an explicit task, enter `/schedule <future epoch millis>|<goal>`; scheduling is never inferred from business keywords.

Do not use this MVP for payments, purchases, account security, verification codes, permission granting, or system settings.

## GitHub updates

The default update repository is `Assangejulian/Muse_Android`. On launch, the app queries its latest public GitHub Release. A newer release must contain an `.apk` asset. Android always requires user confirmation before installing the downloaded APK.
