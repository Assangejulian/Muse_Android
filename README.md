# Muse Android Agent 0.9.0

A private, sideloaded Android 13 automation agent. It observes the active UI through accessibility and optional vision, asks the selected model for one constrained action, validates that action locally, executes it, and independently checks the result.

## MVP capabilities

- Accessibility node observation
- DeepSeek, Qwen, or MiMo planning through OpenAI-compatible APIs
- Default DeepSeek model preset: `deepseek-v4-pro` (Manager may use thinking mode)
- Native `tools` / `tool_calls` planning for DeepSeek and Qwen, with a cached compatibility fallback
- One strictly validated action per model response, with unknown action fields ignored
- Target package allowlist
- Sensitive-page recovery instead of immediate hard stop
- Text and node clicks
- Four-direction swipes
- Focused text input
- Back, app launch, exact text replacement, submit, wait, scroll, and idempotent toggle tools
- Encrypted local API key storage
- Manual stop, cancellable HTTP calls, a twenty-minute deadline, and a bounded 80-tool run budget
- Chinese chat workspace with a configuration drawer
- Persistent conversations with create, pin, and delete actions
- Launchable app catalog exposed through `/list`
- Automatic target app selection from the installed app catalog
- GitHub Release update checks on app launch
- User-confirmed APK download and installation
- Live node-ID clicking with clickable-parent and safe center-tap fallbacks
- Full-screen animated AI-operation border using an accessibility overlay
- Top operation status bar with an always-available stop action
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
- A task-recipe registry with deterministic generic search submission and bounded Bilibili safety guards
- Pre-tool target proof plus idempotent state-transition controls
- App-private SQLite run traces available through `/trace`
- A run console showing the current phase, action, progress, outcome, and full trace
- Model-visible node prioritization, SHA-256 screen fingerprints, adaptive settle polling, and cycle recovery
- Privacy preflight before every model call, PII redaction, strict package locking, and screenshot binding checks
- Neutral app selection that exposes only the goal and installed app catalog until a target package is locked
- Explicit opt-in for screenshot sharing; vision is never enabled merely because a Qwen key exists
- Race-safe run state updates across UI, WorkManager, and accessibility callbacks
- Optional Shizuku 13.1.5 UserService backend with ADB-shell/root identity
- Privileged foreground-app detection plus launch, tap, swipe, Back, Home, and Enter fallbacks
- Explicit user-only `/shell <command>` console with bounded command length, timeout, and output

User-started runs are protected by a foreground service. Exact-alarm special access remains intentionally deferred; scheduled work uses WorkManager and may run later than the parsed time under Android battery optimization.

## Optional privileged backend

Muse can connect to the separately installed Shizuku manager through its official API. When enabled and authorized, a Shizuku UserService runs as ADB shell (or root when Shizuku uses root) and acts as a fallback for the existing typed device actions. The autonomous model loop still receives only the constrained action schema; it does not receive arbitrary shell access.

The raw console is deliberately explicit: only text entered by the user as `/shell <command>` is passed to the privileged service. Commands are limited to 16,000 characters, run for at most 30 seconds, and return at most 64 KiB from each output stream.

Shizuku itself is not bundled into the APK. On Android 11 and newer it can be started from its manager using wireless debugging. A non-root Shizuku service must be started again after a device reboot.

## Build

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat test
.\gradlew.bat lint
```

## First run

1. Install the debug APK.
2. Open Muse.
3. Tap **Accessibility** and enable **Muse Control**.
4. Optional: install and start Shizuku, enable **特权执行后端** in Muse, authorize it, and tap **测试特权连接**.
5. Select DeepSeek or Qwen and enter that provider's API key. The Qwen text preset uses `qwen3.6-flash`; optional vision uses `qwen3-vl-flash`.
6. Optionally set a default target package. Leave it blank for automatic app selection.
7. Enter a narrow, low-risk task in the chat input and tap **发送**.
8. Enter `/list` to inspect the launchable app catalog, or `/shell <command>` to run an explicit privileged command.
9. To schedule an explicit task, enter `/schedule <future epoch millis>|<goal>`; scheduling is never inferred from business keywords.

Do not use this MVP for payments, purchases, account security, verification codes, permission granting, or system settings.

## GitHub updates

The default update repository is `Assangejulian/Muse_Android`. On launch, the app queries its latest public GitHub Release. A newer release must contain an `.apk` asset. Android always requires user confirmation before installing the downloaded APK.
