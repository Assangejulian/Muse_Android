# Muse Android Agent 0.4.9

A private, sideloaded Android 13 automation agent. It observes the active UI through an accessibility service, asks DeepSeek for one constrained action, validates that action locally, executes it, and observes again.

## MVP capabilities

- Accessibility node observation
- DeepSeek `deepseek-chat` planning
- One action per model response
- Target package allowlist
- Sensitive-page blocking
- Text and node clicks
- Four-direction swipes
- Focused text input
- Back, Home, app launch, wait, finish, and fail
- Encrypted local API key storage
- Manual stop and 15-step run limit
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
- Conversation context selected from a one-million-token memory budget
- Larger model output budgets for chat and action planning
- App-private SQLite conversation storage with automatic legacy migration
- OCR-derived next-day scheduling through Android WorkManager
- Strict completion verification and repeated-action recovery
- Optional screenshot planning with a separate OpenAI-compatible vision model
- In-app APK download progress and cancellation

Foreground service and exact-alarm special access are intentionally deferred. Scheduled work uses WorkManager and may run later than the parsed time under Android battery optimization.

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
4. Enter the DeepSeek API key.
5. Optionally set a default target package. Leave it blank for automatic app selection.
6. Enter a narrow, low-risk task in the chat input and tap **发送**.
7. Enter `/list` to inspect the launchable app catalog.

Do not use this MVP for payments, purchases, account security, verification codes, permission granting, or system settings.

## GitHub updates

The default update repository is `Assangejulian/Muse_Android`. On launch, the app queries its latest public GitHub Release. A newer release must contain an `.apk` asset. Android always requires user confirmation before installing the downloaded APK.
