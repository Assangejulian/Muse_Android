# Muse Android Agent 0.15.0

Muse is a private, sideloaded Android control agent. Chat goals run through an **Accessibility UI-tree agent** (observe → plan → act) with optional **Shizuku** shell tools. DeepSeek and other text models use node lists by default; vision/screenshots stay off unless the user enables a vision provider.

Registered capabilities:

- **Accessibility** — live UI hierarchy, click/swipe/input gestures, system-wide cyberpunk progress overlay on other apps
- **Shizuku** — shell identity for `am`/`pm`/`input`/`dumpsys` and the optional Ubuntu runtime
- **Tools for the model** — `launch_app`, `click_node`, `click_text`, `tap_point`, `swipe`, `input_text`, `submit_input`, `ensure_toggle`, `bind_predicate`, `terminal`, `back`, `home`, `wait`, `finish`/`fail`

The Compose UI keeps the cyberpunk terminal look: near-black HUD, cyan/magenta accents, cut-corner controls, scan beam, and `EXEC_CHAIN` progress both in-chat and as an overlay on other pages.

## Product surfaces

- **Chat** — natural-language **device tasks** (UI agent), `/ask` pure Q&A/terminal chat, and `/shell <command>` direct shell.
- **Configure** — accessibility enablement, Shizuku, model provider, Ubuntu environment, updates.
- **个性化** — `memory.md`, context length, max output tokens.

## Device agent model (default Chat)

Each device goal starts `AgentRuntime` when Muse Accessibility is connected:

1. Observe the live UI node tree (and optional OCR text).
2. Model returns one structured action (`android_action` / JSON).
3. Runtime executes via Accessibility gestures and/or Shizuku.
4. Re-observe and continue until verified completion, fail, cancel, or budget exhaust.

A cyberpunk progress overlay (`TYPE_ACCESSIBILITY_OVERLAY`) and a foreground notification stay visible on other apps with step, phase, and ABORT.

Vision stays **off** by default so DeepSeek and other text models work on the node list only.

## Initial environment

Configure can install a real Ubuntu 24.04.4 arm64 environment under `/data/local/tmp/muse` through the connected Shizuku UserService. The installer:

- offers TUNA, USTC, and BFSU as pinned domestic Ubuntu mirrors;
- downloads Ubuntu Base and verifies SHA-256 before extraction;
- identifies downloads as an Android browser client so domestic mirror anti-abuse filters do not reject OkHttp;
- installs any selected Node.js, Python, Java, and SSH packages from `ubuntu-ports`;
- exposes the installed commands through `/data/local/tmp/muse/shims` in every control-terminal PATH;
- preserves Android shell tools such as `am`, `pm`, and `dumpsys` alongside the Linux runtimes.

The embedded arm64 launcher is the unmodified proprietary `proroot` v1.2.8 binary release from `coderredlab/proroot`, used under its stated free-to-use-in-projects terms. Its five release assets are pinned by their published SHA-256 values. The Ubuntu Base archive is pinned to `04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2`.

## Personalization

User memory is stored as UTF-8 Markdown at:

```text
<app files>/personalization/memory.md
```

The file is injected as preference memory for new model requests but cannot override local safety constraints. Context length controls local history trimming. Max output tokens is sent to the configured model service as `max_tokens`.

## Providers

The UI includes presets for DeepSeek, Qwen, and MiMo and accepts any compatible HTTPS Chat Completions endpoint. API keys remain in encrypted shared preferences. Public cleartext HTTP endpoints are rejected; debug builds may use loopback HTTP for local development.

## Updates

Automatic update checks are enabled by default and query the repository's latest GitHub Release once at app startup. Configure also provides a manual check and a visible download progress surface. Muse requires the Release API's SHA-256 asset digest, verifies the downloaded APK before launching Android's package installer, and reuses an already verified download after the user grants unknown-app installation access. Android still requires explicit user confirmation for every sideloaded update.

## First run

1. Install and start the separate Shizuku app using its documented setup method.
2. Install and open Muse.
3. Open **Configure → ACCESSIBILITY**, enable Muse in system accessibility settings (required for UI tools + overlay).
4. Grant Muse access in Shizuku and connect the control terminal.
5. Select a provider (DeepSeek works without vision) and save API key, Base URL, and model.
6. Optionally install Ubuntu runtimes under INITIAL ENVIRONMENT.
7. In Chat, send a device goal in natural language. Use `/ask` for Q&A only, `/shell id` for a shell check.

Do not use Muse for payments, purchases, account-security changes, verification codes, permission grants, credential extraction, or destructive device maintenance.

## Build

```powershell
.\gradlew.bat testDebugUnitTest --no-daemon --console=plain
.\gradlew.bat lintDebug --no-daemon --console=plain
.\gradlew.bat assembleDebug --no-daemon --console=plain
```

The application ID remains `com.androidagent.app`; version `0.14.7` uses versionCode `49` for in-place updates. Environment setup now bootstraps `ca-certificates` over an APT-signed HTTP index before switching the selected mirror back to HTTPS for all remaining packages.
