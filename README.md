# Store

Minimal Android app whose main feature is a self-updater: on launch it checks this
repo's latest GitHub Release, downloads the APK built for the device's CPU (arm64-v8a /
armeabi-v7a / x86 / x86_64), and installs it. If the install is rejected (most commonly
because the new build was signed with a different key than what's on the device), it
uninstalls the old app and prompts the user to tap the already-downloaded APK to finish.

## First-time setup

### 1. Create a release signing key (once, keep it forever)

```
keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias store
```

This key must be reused for **every** release. If you ever regenerate it, existing
installs will hit the uninstall-then-reinstall fallback because Android refuses to
install an update signed with a different key over an existing app.

### 2. Add GitHub Actions secrets

In the repo → Settings → Secrets and variables → Actions, add:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 release.jks` output |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | `store` (or whatever alias you used) |
| `KEY_PASSWORD` | key password |

The workflow uses GitHub's built-in `GITHUB_TOKEN` to publish releases — no personal
access token needs to be stored anywhere.

> Note: a GitHub PAT was pasted in an earlier chat message to set this up. Treat it as
> compromised — revoke/rotate it from GitHub → Settings → Developer settings → Personal
> access tokens. It isn't used by anything in this repo.

### 3. Cut a release

```
git tag v1.0.0
git push origin v1.0.0
```

The `release.yml` workflow builds 4 split APKs (one per ABI, named
`app-<abi>-release.apk`) and publishes them to a GitHub Release named after the tag.
Repeat with `v1.0.1`, `v1.1.0`, etc. — version names must stay numeric `x.y.z` and keep
increasing, since the in-app updater compares them.

## How the updater decides what to install

`UpdateManager` (`app/src/main/java/com/ashrafnaji/store/update/UpdateManager.kt`) calls
`GET https://api.github.com/repos/ashrafnaji/Store/releases/latest`, compares `tag_name`
against `BuildConfig.VERSION_NAME`, and downloads the asset whose filename contains the
device's primary ABI (`Build.SUPPORTED_ABIS[0]`).

Because this app isn't distributed through Play Store, users must allow "install unknown
apps" for it once (Android prompts for this automatically the first time).

## Local development

Open the folder in Android Studio (Hedgehog+) and let it sync — it will generate the
Gradle wrapper automatically. `assembleRelease` falls back to debug signing locally if
`keystore.properties` / the env vars above aren't set, so it builds without secrets; that
build just won't be installable as an *update* over a properly-signed release build.
