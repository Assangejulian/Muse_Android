# Muse Android Agent 0.17.2

Muse is a private, sideloaded Android control agent. The model owns the live route: Accessibility provides fresh UI-tree observation and node actions, while Shizuku provides launch, package/device inspection, bounded shell tools, and the optional Ubuntu runtime. DeepSeek and other text models work without screenshots; vision stays off unless the user explicitly configures a vision provider.

Registered capabilities:

- **Shizuku** — shell identity for bounded `am`/`pm`/`input`/`dumpsys` inspection and the optional Ubuntu runtime
- **Accessibility** — live UI hierarchy, target validation, click/swipe/input actions, and a compact progress overlay on other apps
- **Tools for the model** — `launch_app`, `click_node`, `click_text`, `tap_point`, `swipe`, `input_text`, `submit_input`, `ensure_toggle`, `bind_predicate`, `terminal`, `back`, `home`, `wait`, `finish`/`fail`

The Compose UI offers Catppuccin Mocha dark mode and a sun-warmed Latte light mode, with System / Light / Dark selection, animated palette transitions, rounded controls, and compact two-line progress both in Chat and over other apps.

## Product surfaces

- **Chat** — natural-language **device tasks** (UI agent), `/ask` pure Q&A/terminal chat, and `/shell <command>` direct shell.
- **Configure** — accessibility enablement, Shizuku, model provider, Ubuntu environment, updates.
- **个性化** — theme mode, `memory.md`, context length, max output tokens.

## Device agent model (default Chat)

Each device goal starts the hybrid `AgentRuntime` when Muse Accessibility is connected. If Accessibility is offline but Shizuku remains connected, Muse falls back to the terminal agent instead of rejecting the task.

1. Observe the live UI node tree (and optional OCR text).
2. Model returns one structured action (`android_action` / JSON).
3. The model chooses Accessibility or Shizuku for the next useful step and may switch between installed apps.
4. Re-observe and continue until the Actor declares completion or a real blocker; completion gets one independent model verification.

The runtime uses one advisory goal instead of a speculative Manager plan. Ordinary actions do not require predicate bindings, confirmed retries remain under model control, and local failures return as Actor feedback rather than triggering hidden Back, relaunch, or replan actions. Only safety, stale-target, privacy, installed-package, and unknown-side-effect boundaries remain deterministic.

A compact Catppuccin progress overlay (`TYPE_ACCESSIBILITY_OVERLAY`) and a foreground notification stay visible on other apps with route, action budget, two sanitized progress lines, and Stop.

The 50-turn limit counts only real tool actions. Observation, planning, verification, and replanning use a separate internal control-cycle guard and do not consume the displayed action budget.

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

The application ID remains `com.androidagent.app`; version `0.17.2` uses versionCode `55` for in-place updates. Environment setup bootstraps `ca-certificates` over an APT-signed HTTP index before switching the selected mirror back to HTTPS for all remaining packages.
