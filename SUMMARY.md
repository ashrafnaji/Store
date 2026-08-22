# Store — Project Summary

A lightweight Android app store (`com.ashrafnaji.store`) that self-updates and lets you
publish other apps to it, distributed entirely through GitHub Releases — no server, no
Play Store. Repo: https://github.com/ashrafnaji/Store

## What it does

- **Self-updates**: checks GitHub for a newer version on every launch, downloads the APK
  matching the device's CPU, and installs it automatically.
- **Multi-app catalog**: the main screen lists every app in `catalog.json` (not just
  itself), each as a card with Install / Update / Open / Uninstall buttons.
- **Architecture-aware**: each catalog entry can point at different APK files per CPU
  architecture (arm64-v8a / armeabi-v7a / x86 / x86_64); the app only offers/installs the
  one that matches the device, and hides apps entirely if none of their builds match.
- **Fallback on signature conflict**: if an install fails because the new APK is signed
  differently, it uninstalls the old app and prompts the user to finish installing the new
  one (the file stays available via the system Download provider even after uninstall).

## How to publish an app to the Store

Two Windows desktop tools (Python, stdlib only, no `pip install` needed), both under `tools/`:

- **`tools/admin_panel.py`** — the main way to add/update apps in the catalog. Pick a
  GitHub token, optionally use **"Auto-fill from APK..."** to read name/package/version/
  architectures straight from a file via `aapt`, check which architectures to publish, and
  click **Publish to Store**. It uploads each distinct APK file once (even if shared across
  multiple architectures) and updates `catalog.json` automatically.
- **`tools/release_gui.py`** — builds and publishes a new version of *Store itself*
  (runs the Gradle build, creates a GitHub release, uploads the 4 ABI APKs).

Both save the GitHub token to `%USERPROFILE%\.store_releaser\config.json` if you check
"Remember" — never committed to the repo.

## Release history this session

Store went from nothing to `v1.0.9`, with `latest.json` (read from
`raw.githubusercontent.com`, not the rate-limited `api.github.com`) always pointing at the
current version. Catalog currently includes: Store itself, Simple Keyboard, YouTube
ReVanced, App Manager, MoreLocale 2 (test).

## Bugs found and fixed

- **GitHub API rate limiting**: self-update checks originally called
  `api.github.com/.../releases/latest`, which caps unauthenticated requests at 60/hour per
  IP — easy to exhaust with several units on one network. Switched to a small `latest.json`
  manifest read from the CDN (`raw.githubusercontent.com`), which isn't rate-limited.
- **Duplicate cards on first launch**: `onResume` firing right after `onCreate` raced the
  initial catalog load, rendering every card twice. Fixed with a generation counter that
  only lets the latest load render.
- **Cards stuck on "Installing..."**: the system install-confirmation dialog runs outside
  the app's process, so nothing told the UI the outcome. Added a Refresh button and
  re-check installed versions in `onResume`.
- **Literal `"null"` shown as a description**: `org.json`'s `optString()` turns an
  explicit JSON `null` (which is what GitHub returns for a repo with no description) into
  the string `"null"` instead of an actual null. Fixed with an explicit `isNull()` check.
- **Cards permanently stuck on "Loading..."**: that text was meant as a transient state
  only for Store's own self-fetched description; other catalog entries with a blank
  description fell into the same branch but never got a follow-up update. Now shows "No
  description available." immediately for non-self entries.
- **x86-only apps showing as installable on arm64 devices**: `resolveDownloadUrl()` fell
  back to the plain `downloadUrl` field even when the `downloadUrls` per-architecture map
  existed but had no match for the device — the admin panel always sets `downloadUrl` to
  one of the uploaded assets for backward compatibility, so an x86_64-only upload was
  resolving (and installing) on arm64 devices too. Fixed: `downloadUrl` is now only a
  fallback for entries with no `downloadUrls` map at all.
- **Incompatible apps still appearing in the list**: cards rendered unconditionally even
  when no uploaded build matched the device's CPU — incompatibility only surfaced after
  tapping Install. Now entries with no compatible build and not already installed are
  skipped entirely, like a real app store.
- **Admin panel crash on some APKs**: `aapt`'s output is UTF-8, but `subprocess.run`
  defaulted to the Windows codepage (cp1252) to decode it — any non-Latin byte in a
  permission/label string crashed with `UnicodeDecodeError` → `TypeError`. Fixed by
  decoding as UTF-8 explicitly.
- **Universal APKs mis-detected as single-architecture**: `aapt` splits "supports every
  ABI" across two output lines (`native-code` + `alt-native-code`); only the first was
  parsed, so a genuinely universal build could be wrongly tagged as one architecture.
- **Admin panel auto-fill "stopping" after one use**: name/package/version fields only
  filled in when empty (to avoid overwriting when adding a second arch build for the *same*
  app), so a second auto-fill for a *different* app silently did nothing to those fields —
  looked broken until the tool was restarted. Now resets automatically when the detected
  package differs from what's in the form, plus a manual "Clear Form" button.
- **Duplicate uploads**: a universal APK assigned to all 4 architecture slots was uploaded
  up to 4 times (once per slot). Now each distinct file (by resolved path) uploads once,
  named after every architecture it covers, shared across all of them in `downloadUrls`.

## Known limitations (not bugs, inherent to the platform)

- **Silent install/uninstall isn't possible from a normal app.** Android requires a
  system confirmation dialog for both; skipping it needs the app to be privileged/system
  or to use something like Shizuku/root (offered but not yet built — see below).
- **GitHub's raw-content CDN (`raw.githubusercontent.com`) has per-edge caching.**
  Different networks can see a newly-published `catalog.json`/`latest.json` several minutes
  apart. This isn't fixable from the app side; it always resolves on its own.
- One bench unit hit a firmware-level crash loop (two unrelated OEM system apps
  continuously crash-restarting) that could kill short-lived UI like the install/uninstall
  confirmation dialog before it renders — confirmed as a device firmware issue (missing
  system package + framework/app version mismatch), not a Store bug, via direct `pm
  install`/`pm uninstall` testing.

## Possible next steps (discussed, not built yet)

- Shizuku (or root) support for silent installs/uninstalls, immune to the confirmation-
  dialog fragility above — same mechanism third-party tools like App Manager use.
- A dedicated release keystore (current builds still fall back to debug signing since none
  has been generated yet — see README's "First-time setup").
