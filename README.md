# Muse Android Agent 0.8.1

A private, sideloaded Android 13 automation agent. It observes the active UI through accessibility and optional vision, asks the selected model for one constrained action, validates that action locally, executes it, and independently checks the result.

## MVP capabilities

- Accessibility node observation
- DeepSeek, Qwen, or MiMo planning through OpenAI-compatible APIs
- Native `tools` / `tool_calls` planning for DeepSeek and Qwen, with a cached compatibility fallback
- One strictly validated action per model response
- Target package allowlist
- Sensitive-page blocking
- Text and node clicks
- Four-direction swipes
- Focused text input
- Back, app launch, exact text replacement, submit, wait, scroll, and idempotent toggle tools
- Encrypted local API key storage
- Manual stop, cancellable HTTP calls, a five-minute deadline, and a bounded 24-tool run budget
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
- `/chat` and `/run` overrides for ambiguous messages
- Bundled on-device Chinese OCR fallback for inaccessible visible text
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
- A reserved extension seam for optional task recipes; the core runtime contains no platform-specific workflow
- Pre-tool target proof plus idempotent state-transition controls
- App-private SQLite run traces available through `/trace`
- A run console showing the current phase, action, progress, outcome, and full trace
- Model-visible node prioritization, SHA-256 screen fingerprints, adaptive settle polling, and cycle recovery
- Privacy preflight before every model call, PII redaction, strict package locking, and screenshot binding checks
- Neutral app selection that exposes only the goal and installed app catalog until a target package is locked
- Explicit opt-in for screenshot sharing; vision is never enabled merely because a Qwen key exists
- Race-safe run state updates across UI, WorkManager, and accessibility callbacks

Foreground service and exact-alarm special access are intentionally deferred. Scheduled work uses WorkManager and may run later than the parsed time under Android battery optimization.

## Why there is no terminal

Muse intentionally does not expose an arbitrary shell. A normal Android app terminal still runs under the Muse app UID and cannot reliably control other apps; granting Shizuku or root access would materially expand the trust boundary. The current typed accessibility tools are observable, locally validated, package-bound, and auditable. A future terminal bridge should therefore be an explicit optional backend, not part of the default agent loop.

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
4. Select DeepSeek or Qwen and enter that provider's API key. The Qwen text preset uses `qwen3.6-flash`; optional vision uses `qwen3-vl-flash`.
5. Optionally set a default target package. Leave it blank for automatic app selection.
6. Enter a narrow, low-risk task in the chat input and tap **发送**.
7. Enter `/list` to inspect the launchable app catalog.
8. To schedule an explicit task, enter `/schedule <future epoch millis>|<goal>`; scheduling is never inferred from business keywords.

Do not use this MVP for payments, purchases, account security, verification codes, permission granting, or system settings.

## GitHub updates

The default update repository is `Assangejulian/Muse_Android`. On launch, the app queries its latest public GitHub Release. A newer release must contain an `.apk` asset. Android always requires user confirmation before installing the downloaded APK.
