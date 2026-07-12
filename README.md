# Android Agent MVP 0.4.0

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

Scheduling, screen wake, screenshots, Room logs, foreground service, and visual planning are intentionally deferred until the target tablet passes the capability probe.

## Build

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat test
.\gradlew.bat lint
```

## First run

1. Install the debug APK.
2. Open Android Agent.
3. Tap **Accessibility** and enable **Android Agent Control**.
4. Enter the DeepSeek API key.
5. Optionally set a default target package. Leave it blank for automatic app selection.
6. Enter a narrow, low-risk task in the chat input and tap **发送**.
7. Enter `/list` to inspect the launchable app catalog.

Do not use this MVP for payments, purchases, account security, verification codes, permission granting, or system settings.

## GitHub updates

The default update repository is `Assangejulian/Muse_Android`. On launch, the app queries its latest public GitHub Release. A newer release must contain an `.apk` asset. Android always requires user confirmation before installing the downloaded APK.
