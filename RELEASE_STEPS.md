# Releasing a new Store version — exact steps

This documents precisely what happens **after `gradle assembleRelease` produces the 4 ABI
APKs**, so another agent (or a human) can reproduce a release without guessing. This is the
manual/API version of what `tools/release_gui.py` already automates — use that tool instead
if a human is doing this interactively. This doc is for when an agent needs to do it directly.

Replace `X.Y.Z` below with the actual version (e.g. `1.0.13`), and `N` with the next
integer versionCode (one higher than the last release).

## Prerequisites

- A GitHub token with `Contents: Read and write` on `ashrafnaji/Store`, passed as `$TOKEN`
  in the commands below. **Never commit this token or paste it into a file in the repo.**
- Already built: `app/build/outputs/apk/release/app-{arm64-v8a,armeabi-v7a,x86,x86_64}-release.apk`,
  built with:
  ```
  export APP_VERSION_NAME="X.Y.Z"
  export APP_VERSION_CODE="N"
  gradle assembleRelease --console=plain --no-daemon
  ```
  (`APP_VERSION_CODE` just needs to be higher than the previous release's; using a running
  counter, e.g. 1 → 2 → 3 across releases, is simplest.)

## 1. Create the GitHub release

```bash
TOKEN="<github token>"
curl -s -X POST https://api.github.com/repos/ashrafnaji/Store/releases \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -d '{"tag_name":"vX.Y.Z","name":"Store vX.Y.Z","generate_release_notes":false}' \
  > /tmp/release.json
grep -E '"id"|"upload_url"' /tmp/release.json
```

Note the numeric `"id"` printed — that's the `<RELEASE_ID>` used in step 2.

## 2. Upload all 4 APKs as release assets

```bash
cd app/build/outputs/apk/release
for f in *.apk; do
  curl -s -X POST "https://uploads.github.com/repos/ashrafnaji/Store/releases/<RELEASE_ID>/assets?name=$f" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/vnd.android.package-archive" \
    --data-binary "@$f" | grep -o '"state":"[a-z]*"'
done
```

Each should print `"state":"uploaded"`. If re-publishing the same tag (re-running a
release), delete the old same-named asset first via `DELETE
https://api.github.com/repos/ashrafnaji/Store/releases/assets/<ASSET_ID>` — GitHub
rejects a duplicate asset name in one release.

## 3. Update `latest.json` and push it to `main`

This is what the in-app self-updater actually reads (from
`raw.githubusercontent.com`, not the GitHub REST API — see `UpdateManager.kt`'s
`fetchLatestRelease()` for why). **A release isn't "live" to the app until this file is
updated and pushed.**

Write `latest.json` at the repo root:
```json
{
  "version": "X.Y.Z",
  "assets": {
    "arm64-v8a": "https://github.com/ashrafnaji/Store/releases/download/vX.Y.Z/app-arm64-v8a-release.apk",
    "armeabi-v7a": "https://github.com/ashrafnaji/Store/releases/download/vX.Y.Z/app-armeabi-v7a-release.apk",
    "x86": "https://github.com/ashrafnaji/Store/releases/download/vX.Y.Z/app-x86-release.apk",
    "x86_64": "https://github.com/ashrafnaji/Store/releases/download/vX.Y.Z/app-x86_64-release.apk"
  }
}
```

Then:
```bash
git add latest.json
git commit -m "Point latest.json at vX.Y.Z"
git push origin main
```

If the push is rejected (remote has commits you don't have locally — common if the admin
panel published a catalog change in parallel), `git fetch origin main` then `git rebase
FETCH_HEAD` before pushing again; conflicts are unlikely since `latest.json` and
`catalog.json` are edited independently.

## Pushing to GitHub without a stored credential

Don't add the token to a remote URL with `-u` (git then stores it in plaintext in
`.git/config` — the token must be scrubbed if that happens: `git config
branch.main.remote origin` to reset). Instead push explicitly each time:
```bash
git -c credential.helper= push https://ashrafnaji:$TOKEN@github.com/ashrafnaji/Store.git main
```

## Verifying the release actually reached devices

`raw.githubusercontent.com` has per-CDN-edge caching that can lag several minutes (has
happened repeatedly this project) — a device on a different network than the one that just
published may see a stale `latest.json`/`catalog.json` for a while. This is normal and not
a bug; confirm the *published* content is correct with:
```bash
curl -s https://raw.githubusercontent.com/ashrafnaji/Store/main/latest.json
```
If that shows the right version but a specific device doesn't, it's edge-cache lag on that
device's network path — wait and retry, don't debug the app.

## Full end-to-end example (what this looked like for v1.0.12)

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot"
export APP_VERSION_NAME="1.0.12"
export APP_VERSION_CODE="13"
gradle assembleRelease --console=plain --no-daemon

TOKEN="<token>"
curl -s -X POST https://api.github.com/repos/ashrafnaji/Store/releases \
  -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" \
  -d '{"tag_name":"v1.0.12","name":"Store v1.0.12","generate_release_notes":false}' \
  > /tmp/release.json
# -> id: 374874125

cd app/build/outputs/apk/release
for f in *.apk; do
  curl -s -X POST "https://uploads.github.com/repos/ashrafnaji/Store/releases/374874125/assets?name=$f" \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/vnd.android.package-archive" \
    --data-binary "@$f"
done
cd -

# write latest.json with version 1.0.12 and the 4 download URLs above, then:
git add latest.json
git commit -m "Point latest.json at v1.0.12"
git -c credential.helper= push https://ashrafnaji:$TOKEN@github.com/ashrafnaji/Store.git main
```
