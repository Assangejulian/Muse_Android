# Muse Android Agent 0.14.4

Muse is a private, sideloaded Android control terminal. The app combines an OpenAI-compatible model with an externally installed Shizuku service, so device operations run through Android's `shell` identity without Termux or an embedded ADB client.

The active product no longer registers or requests an Android Accessibility Service. Muse is now terminal-first and terminal-only: the model may inspect and operate the device with bounded shell commands, while deterministic local policy blocks destructive and security-sensitive command classes.

The Compose UI uses a cyberpunk terminal system: near-black HUD surfaces, cyan operational state, magenta identity accents, cut-corner controls, a restrained scan beam, and a visible `EXEC_CHAIN n/50` indicator.

## Product surfaces

- **Chat** — persistent conversations, normal model replies, bounded terminal-agent loops, and explicit `/shell <command>` execution.
- **Configure** — automatic GitHub Release updates, Shizuku authorization/connection, model provider settings, environment installation, PATH configuration, and live probing.
- **个性化** — app-private `memory.md`, configurable context length, and maximum output tokens.

## Terminal model

For each user turn, the model must choose one JSON action:

- `run`: propose one shell command and a short progress summary.
- `finish`: return the final Chinese response.

Muse validates every proposed command locally, runs it through the Shizuku UserService, then returns the exit code, bounded stdout/stderr, timeout state, and duration to the model. A request is limited to 50 terminal turns; each command is limited to 30 seconds and the UserService caps retained output.

The model never receives a general-purpose unbounded process handle. Local policy rejects recursive forced deletion, package clear/uninstall, reboot or shutdown, security/global settings mutation, device-policy commands, and privilege escalation commands.

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
3. Open **Configure**, grant Muse access in Shizuku, and connect the control terminal.
4. Select a provider and save its API key, Base URL, and model.
5. Choose a domestic mirror and the desired runtimes, then install and probe the initial environment.
6. Optionally edit `memory.md`, context length, and max output tokens under **个性化**.
7. Use Chat normally, or enter `/shell id` for an explicit connection check.

Do not use Muse for payments, purchases, account-security changes, verification codes, permission grants, credential extraction, or destructive device maintenance.

## Build

```powershell
.\gradlew.bat testDebugUnitTest --no-daemon --console=plain
.\gradlew.bat lintDebug --no-daemon --console=plain
.\gradlew.bat assembleDebug --no-daemon --console=plain
```

The application ID remains `com.androidagent.app`; version `0.14.4` uses versionCode `46` for in-place updates.
