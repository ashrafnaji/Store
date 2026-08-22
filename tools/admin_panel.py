"""
Admin panel (Tkinter, stdlib only) for publishing any app into the Store catalog.

Pick per-architecture APK files (arm64-v8a / armeabi-v7a / x86 / x86_64 -- fill in only the
ones you have), upload them as assets on a GitHub release, and add/update that app's entry
in catalog.json so it shows up as a card in the Store app on customer devices.

Run with:  python tools/admin_panel.py

Reuses the same saved-token file as release_gui.py: %USERPROFILE%\\.store_releaser\\config.json.
"""
import base64
import json
import mimetypes
import os
import re
import subprocess
import sys
import threading
import urllib.error
import urllib.request
import zipfile
from pathlib import Path
from tkinter import (
    BOTH, END, LEFT, RIGHT, X, Y, W, StringVar, BooleanVar,
    Tk, Frame, Label, Entry, Button, Checkbutton, Text, Scrollbar, filedialog
)

REPO_OWNER = "ashrafnaji"
REPO_NAME = "Store"
ARCHS = ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"]
CONFIG_PATH = Path.home() / ".store_releaser" / "config.json"


def find_aapt():
    """Locates aapt(.exe) from the Android SDK, if one is installed on this machine."""
    from shutil import which
    found = which("aapt") or which("aapt.exe")
    if found:
        return found

    sdk_roots = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")]
    sdk_roots.append(str(Path.home() / "AppData" / "Local" / "Android" / "Sdk"))
    for root in sdk_roots:
        if not root:
            continue
        build_tools = Path(root) / "build-tools"
        if not build_tools.exists():
            continue
        candidates = sorted(build_tools.glob("*/aapt.exe"), reverse=True) or sorted(build_tools.glob("*/aapt"), reverse=True)
        if candidates:
            return str(candidates[0])
    return None


def find_aapt2():
    """Locates aapt2 beside aapt so compiled resource file paths can be resolved."""
    from shutil import which
    found = which("aapt2") or which("aapt2.exe")
    if found:
        return found

    aapt = find_aapt()
    if aapt:
        for sibling_name in ("aapt2.exe", "aapt2"):
            sibling = Path(aapt).with_name(sibling_name)
            if sibling.exists():
                return str(sibling)
    return None


def run_android_tool(command):
    result = subprocess.run(
        command, capture_output=True, text=True, timeout=30,
        encoding="utf-8", errors="replace"
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or f"Command failed: {' '.join(command)}")
    return result.stdout


def extract_apk_icon(apk_path: str):
    """Returns the APK's best raster launcher icon bytes, extension, and resource path."""
    aapt = find_aapt()
    aapt2 = find_aapt2()
    if not aapt or not aapt2:
        return None

    manifest = run_android_tool([aapt, "dump", "xmltree", apk_path, "AndroidManifest.xml"])
    application_attributes = []
    manifest_lines = manifest.splitlines()
    for index, line in enumerate(manifest_lines):
        application_match = re.match(r"^(\s*)E: application(?:\s|$)", line)
        if not application_match:
            continue
        attribute_prefix = application_match.group(1) + "  A:"
        for attribute_line in manifest_lines[index + 1:]:
            if attribute_line.startswith(attribute_prefix):
                application_attributes.append(attribute_line)
                continue
            if attribute_line.lstrip().startswith("E:"):
                break
        break
    icon_ids = re.findall(
        r"android:(?:icon|roundIcon).*?=@(0x[0-9a-fA-F]+)",
        "\n".join(application_attributes),
    )
    resources = run_android_tool([aapt2, "dump", "resources", apk_path])

    resource_files = {}
    current_id = None
    for line in resources.splitlines():
        resource_match = re.match(r"\s*resource (0x[0-9a-fA-F]+)\s", line)
        if resource_match:
            current_id = resource_match.group(1).lower()
            continue
        file_match = re.match(r"\s*\(([^)]*)\) \(file\) (\S+) type=(\S+)", line)
        if current_id and file_match:
            resource_files.setdefault(current_id, []).append((file_match.group(1), file_match.group(2)))

    density_rank = {
        "xxxhdpi": 6, "xxhdpi": 5, "xhdpi": 4, "hdpi": 3, "mdpi": 2, "ldpi": 1,
    }

    def raster_candidates(resource_id):
        candidates = []
        for config, resource_path in resource_files.get(resource_id.lower(), []):
            lower_path = resource_path.lower()
            if lower_path.endswith(".9.png") or not lower_path.endswith((".png", ".webp", ".jpg", ".jpeg")):
                continue
            rank = max((score for density, score in density_rank.items() if density in config), default=0)
            candidates.append((rank, resource_path))
        return sorted(candidates, reverse=True)

    # Prefer the main icon, then roundIcon. If either is adaptive-only, follow its foreground
    # drawable reference as a final fallback.
    candidate_ids = [resource_id.lower() for resource_id in icon_ids]
    for resource_id in list(candidate_ids):
        for _, xml_path in resource_files.get(resource_id, []):
            if not xml_path.lower().endswith(".xml"):
                continue
            tree = run_android_tool([aapt, "dump", "xmltree", apk_path, xml_path])
            drawable_ids = re.findall(r"android:drawable.*?=@(0x[0-9a-fA-F]+)", tree)
            candidate_ids.extend(resource_id.lower() for resource_id in reversed(drawable_ids))

    with zipfile.ZipFile(apk_path) as apk:
        for resource_id in candidate_ids:
            candidates = raster_candidates(resource_id)
            if not candidates:
                continue
            resource_path = candidates[0][1]
            extension = Path(resource_path).suffix.lower().lstrip(".")
            if extension == "jpeg":
                extension = "jpg"
            return {
                "data": apk.read(resource_path),
                "extension": extension,
                "resource_path": resource_path,
                "apk_path": str(Path(apk_path).resolve()),
            }
    return None


def inspect_apk(apk_path: str):
    """
    Reads package name, versionName, app label, and supported ABIs straight out of the APK
    using aapt (part of the Android SDK build-tools). Returns None if aapt isn't available --
    callers should fall back to asking the admin to fill fields in manually.

    An APK with no "native-code:" line has no native libraries, so it runs on every ABI
    ("sometimes 1 apk works on all") -- that's reported as abis=[] (meaning: all of them).
    """
    aapt = find_aapt()
    if not aapt:
        return None

    # aapt's output is UTF-8 regardless of the system codepage; on Windows, text=True defaults
    # to cp1252, which raises UnicodeDecodeError on any non-Latin byte (e.g. in a permission or
    # label string) and silently leaves .stdout as None, crashing the regex calls below.
    output = subprocess.run(
        [aapt, "dump", "badging", apk_path],
        capture_output=True, text=True, timeout=30, encoding="utf-8", errors="replace"
    ).stdout

    package_match = re.search(r"package: name='([^']+)'.*?versionName='([^']*)'", output)
    label_match = re.search(r"application-label:'([^']*)'", output)
    native_code_match = re.search(r"native-code: (.+)", output)
    # A universal/fat APK with native libs for every ABI gets split across two lines: aapt
    # reports one ABI as "native-code" and the rest as "alt-native-code" -- both mean "present
    # in this APK", so they need to be combined, not just the first.
    alt_native_code_match = re.search(r"alt-native-code: (.+)", output)

    abis = []
    if native_code_match:
        abis = re.findall(r"'([^']+)'", native_code_match.group(1))
    if alt_native_code_match:
        abis += re.findall(r"'([^']+)'", alt_native_code_match.group(1))

    return {
        "package_name": package_match.group(1) if package_match else "",
        "version_name": package_match.group(2) if package_match else "",
        "app_label": label_match.group(1) if label_match else "",
        "abis": abis,  # empty list == no native libs == works on every architecture
    }


def load_saved_token():
    try:
        return json.loads(CONFIG_PATH.read_text()).get("token", "")
    except Exception:
        return ""


def save_token(token: str):
    CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
    CONFIG_PATH.write_text(json.dumps({"token": token}))


def slugify(name: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")
    return slug or "app"


def api_request(url, token, method="GET", data=None, content_type="application/json"):
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/vnd.github+json")
    if data is not None:
        req.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            body = resp.read()
            return json.loads(body.decode("utf-8")) if body else {}
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"GitHub API error {e.code} for {url}: {body}") from None


def get_or_create_release(token, tag, name, log):
    try:
        release = api_request(f"https://api.github.com/repos/{REPO_OWNER}/{REPO_NAME}/releases/tags/{tag}", token)
        log(f"Using existing release {tag}")
        return release
    except RuntimeError:
        pass
    payload = json.dumps({"tag_name": tag, "name": name, "generate_release_notes": False}).encode("utf-8")
    release = api_request(
        f"https://api.github.com/repos/{REPO_OWNER}/{REPO_NAME}/releases",
        token, method="POST", data=payload
    )
    log(f"Created release {tag}")
    return release


def delete_asset_if_present(token, release, asset_name, log):
    for asset in release.get("assets", []):
        if asset["name"] == asset_name:
            log(f"Replacing existing asset {asset_name}...")
            api_request(asset["url"], token, method="DELETE")
            return


def upload_asset(token, upload_url, file_path, asset_name, log):
    mime = mimetypes.guess_type(asset_name)[0] or "application/vnd.android.package-archive"
    data = Path(file_path).read_bytes()
    upload_asset_data(token, upload_url, data, asset_name, mime, log)


def upload_asset_data(token, upload_url, data, asset_name, mime, log):
    log(f"Uploading {asset_name} ({len(data) / 1024:.0f} KB)...")
    api_request(f"{upload_url}?name={asset_name}", token, method="POST", data=data, content_type=mime)
    log(f"  done.")


def fetch_catalog(token, log):
    contents_url = f"https://api.github.com/repos/{REPO_OWNER}/{REPO_NAME}/contents/catalog.json"
    info = api_request(contents_url, token)
    items = json.loads(base64.b64decode(info["content"]).decode("utf-8"))
    return items, info["sha"]


def push_catalog(token, items, sha, message, log):
    content_b64 = base64.b64encode(json.dumps(items, indent=2).encode("utf-8")).decode("ascii")
    contents_url = f"https://api.github.com/repos/{REPO_OWNER}/{REPO_NAME}/contents/catalog.json"
    payload = {"message": message, "content": content_b64, "branch": "main", "sha": sha}
    api_request(contents_url, token, method="PUT", data=json.dumps(payload).encode("utf-8"))
    log("catalog.json updated.")


def publish_app(token, app_id, name, package_name, version, description, arch_files, icon_asset, log):
    tag = f"{app_id}-v{version}"
    release = get_or_create_release(token, tag, f"{name} v{version}", log)
    upload_url = release["upload_url"].split("{")[0]

    # Multiple architecture slots often point at the exact same local file -- a universal APK
    # with no native libs (auto-fill assigns it to all four), or one that bundles native code
    # for every ABI in a single build. Upload each distinct file only once instead of
    # re-uploading identical bytes per architecture, and point every arch that shares it at the
    # same asset.
    groups = {}
    for arch, file_path in arch_files.items():
        if not file_path:
            continue
        key = str(Path(file_path).resolve())
        groups.setdefault(key, {"path": file_path, "archs": []})["archs"].append(arch)

    download_urls = {}
    for group in groups.values():
        archs = group["archs"]
        suffix = "universal" if set(archs) == set(arch_files.keys()) else "-".join(sorted(archs))
        asset_name = f"{app_id}-{suffix}.apk"
        delete_asset_if_present(token, release, asset_name, log)
        upload_asset(token, upload_url, group["path"], asset_name, log)
        url = f"https://github.com/{REPO_OWNER}/{REPO_NAME}/releases/download/{tag}/{asset_name}"
        for arch in archs:
            download_urls[arch] = url

    if not download_urls:
        raise RuntimeError("No APK files selected for any architecture.")

    if icon_asset is None:
        raise RuntimeError("Could not extract a raster launcher icon from the selected APK.")
    icon_extension = icon_asset["extension"]
    icon_asset_name = f"{app_id}-icon.{icon_extension}"
    icon_mime = {
        "png": "image/png",
        "webp": "image/webp",
        "jpg": "image/jpeg",
    }.get(icon_extension, "application/octet-stream")
    delete_asset_if_present(token, release, icon_asset_name, log)
    upload_asset_data(token, upload_url, icon_asset["data"], icon_asset_name, icon_mime, log)
    icon_url = f"https://github.com/{REPO_OWNER}/{REPO_NAME}/releases/download/{tag}/{icon_asset_name}"

    log("Updating catalog.json...")
    items, sha = fetch_catalog(token, log)
    items = [i for i in items if i.get("id") != app_id]
    items.append({
        "id": app_id,
        "name": name,
        "packageName": package_name,
        "type": "static",
        "version": version,
        "downloadUrl": next(iter(download_urls.values())),
        "downloadUrls": download_urls,
        "description": description,
        "iconUrl": icon_url,
    })
    push_catalog(token, items, sha, f"Add/update {name} {version} in catalog", log)

    log(f"\nPublished: {release['html_url']}")


class AdminPanel:
    def __init__(self, root: Tk):
        self.root = root
        root.title("Store - Admin Panel")
        root.geometry("700x640")

        form = Frame(root, padx=10, pady=10)
        form.pack(fill=X)

        self.name_var = StringVar()
        self.package_var = StringVar()
        self.version_var = StringVar()
        self.id_var = StringVar()
        self.icon_status_var = StringVar(value="Not extracted")
        self.icon_asset = None
        self.token_var = StringVar(value=load_saved_token())
        self.remember_var = BooleanVar(value=bool(load_saved_token()))
        self.arch_vars = {arch: StringVar() for arch in ARCHS}
        self.arch_check_vars = {arch: BooleanVar() for arch in ARCHS}

        autofill_row = Frame(form)
        autofill_row.pack(fill=X, pady=(0, 10))
        Button(autofill_row, text="Auto-fill from APK...", command=self.autofill_from_apk).pack(side=LEFT)
        Button(autofill_row, text="Clear Form", command=self.reset_form).pack(side=LEFT, padx=(6, 0))
        Label(
            autofill_row,
            text="  reads name/package/version and detects supported architectures automatically",
            fg="gray"
        ).pack(side=LEFT)

        self._row(form, "App name:", self.name_var)
        self._row(form, "Package name:", self.package_var, placeholder="com.example.app")
        self._row(form, "Version (e.g. 1.0.0):", self.version_var)
        self._row(form, "Catalog ID (optional):", self.id_var, placeholder="auto-generated from name")

        icon_row = Frame(form)
        icon_row.pack(fill=X, pady=4)
        Label(icon_row, text="App icon:", width=20, anchor="w").pack(side=LEFT)
        Label(icon_row, textvariable=self.icon_status_var, anchor="w", fg="gray").pack(side=LEFT, fill=X, expand=True)
        Button(icon_row, text="Extract from APK...", command=self.pick_icon_apk).pack(side=RIGHT)

        Label(form, text="Description:", anchor="w").pack(fill=X, pady=(8, 0))
        self.description_text = Text(form, height=3, wrap="word")
        self.description_text.pack(fill=X)

        Label(form, text="Architectures to publish:", anchor="w").pack(fill=X, pady=(12, 4))
        for arch in ARCHS:
            self._arch_row(form, arch)

        self._row(form, "GitHub token:", self.token_var, show="*")
        Checkbutton(
            form, text="Remember token on this PC", variable=self.remember_var
        ).pack(anchor="w", pady=(0, 8))

        self.action_button = Button(form, text="Publish to Store", command=self.start)
        self.action_button.pack(anchor="w")

        log_frame = Frame(root, padx=10, pady=10)
        log_frame.pack(fill=BOTH, expand=True)
        scrollbar = Scrollbar(log_frame)
        scrollbar.pack(side=RIGHT, fill=Y)
        self.log_text = Text(log_frame, wrap="word", yscrollcommand=scrollbar.set)
        self.log_text.pack(fill=BOTH, expand=True)
        scrollbar.config(command=self.log_text.yview)

    def _row(self, parent, label_text, var, show=None, placeholder=None):
        row = Frame(parent)
        row.pack(fill=X, pady=4)
        Label(row, text=label_text, width=20, anchor="w").pack(side=LEFT)
        entry = Entry(row, textvariable=var, show=show)
        entry.pack(side=LEFT, fill=X, expand=True)
        if placeholder:
            Label(row, text=placeholder, fg="gray", anchor="w").pack(side=LEFT, padx=(6, 0))

    def _arch_row(self, parent, arch):
        row = Frame(parent)
        row.pack(fill=X, pady=2)
        Checkbutton(row, variable=self.arch_check_vars[arch]).pack(side=LEFT)
        Label(row, text=arch, width=13, anchor="w").pack(side=LEFT)
        path_label = Label(row, textvariable=self.arch_vars[arch], anchor="w", fg="gray")
        path_label.pack(side=LEFT, fill=X, expand=True)
        Button(row, text="Browse...", command=lambda a=arch: self.pick_file(a)).pack(side=RIGHT)

    def pick_file(self, arch):
        path = filedialog.askopenfilename(title=f"Select APK for {arch}", filetypes=[("APK files", "*.apk")])
        if path:
            self.arch_vars[arch].set(path)
            self.arch_check_vars[arch].set(True)

    def pick_icon_apk(self):
        path = filedialog.askopenfilename(title="Select APK to extract icon", filetypes=[("APK files", "*.apk")])
        if path:
            self.extract_icon(path)

    def extract_icon(self, path):
        try:
            icon_asset = extract_apk_icon(path)
        except Exception as error:
            self.icon_asset = None
            self.icon_status_var.set("Extraction failed")
            self.log(f"Could not extract icon from {Path(path).name}: {error}")
            return

        if icon_asset is None:
            self.icon_asset = None
            self.icon_status_var.set("No raster icon found")
            self.log(f"Could not find a raster launcher icon in {Path(path).name}.")
            return

        self.icon_asset = icon_asset
        self.icon_status_var.set(f"{Path(path).name} -> {Path(icon_asset['resource_path']).name}")
        self.log(f"{Path(path).name}: extracted launcher icon {icon_asset['resource_path']}.")

    def reset_form(self):
        self.name_var.set("")
        self.package_var.set("")
        self.version_var.set("")
        self.id_var.set("")
        self.icon_asset = None
        self.icon_status_var.set("Not extracted")
        self.description_text.delete("1.0", END)
        for arch in ARCHS:
            self.arch_vars[arch].set("")
            self.arch_check_vars[arch].set(False)

    def autofill_from_apk(self):
        path = filedialog.askopenfilename(title="Select APK to inspect", filetypes=[("APK files", "*.apk")])
        if not path:
            return

        info = inspect_apk(path)
        if info is None:
            self.log(
                "Couldn't auto-detect: no 'aapt' found on this PC (part of the Android SDK "
                "build-tools). Fill in the fields and pick per-architecture files manually."
            )
            return

        # A second auto-fill for a *different* package means the admin is starting a new app
        # entry -- reset everything first, otherwise the form silently keeps the previous app's
        # name/package/version (they only ever got filled once, the first time the fields were
        # empty) and it looks like auto-fill "stopped working" without restarting the tool.
        current_package = self.package_var.get().strip()
        if current_package and info["package_name"] and info["package_name"] != current_package:
            self.reset_form()

        if info["app_label"]:
            self.name_var.set(info["app_label"])
        if info["package_name"]:
            self.package_var.set(info["package_name"])
        if info["version_name"]:
            self.version_var.set(info["version_name"])
        self.extract_icon(path)

        abis = info["abis"]
        if abis:
            matched = [a for a in abis if a in self.arch_vars]
            for arch in matched:
                self.arch_vars[arch].set(path)
                self.arch_check_vars[arch].set(True)
            self.log(f"{Path(path).name}: detected architectures {', '.join(matched) or abis} -> checked matching slot(s).")
        else:
            # No native-code line means no native libraries -- this single APK runs on every ABI.
            for arch in ARCHS:
                self.arch_vars[arch].set(path)
                self.arch_check_vars[arch].set(True)
            self.log(f"{Path(path).name}: no native libraries detected -> this APK works on all architectures, checked every slot.")

    def log(self, message: str):
        def append():
            self.log_text.insert(END, message + "\n")
            self.log_text.see(END)
        self.root.after(0, append)

    def start(self):
        name = self.name_var.get().strip()
        package_name = self.package_var.get().strip()
        version = self.version_var.get().strip()
        app_id = self.id_var.get().strip() or slugify(name)
        description = self.description_text.get("1.0", END).strip()
        token = self.token_var.get().strip()
        # Only architectures with both a file selected and their checkbox ticked are published --
        # this lets an auto-fill assign a universal APK to all four slots while still letting the
        # admin uncheck the ones they don't actually want to publish for.
        arch_files = {
            arch: self.arch_vars[arch].get().strip()
            for arch in ARCHS
            if self.arch_check_vars[arch].get() and self.arch_vars[arch].get().strip()
        }

        if not name or not package_name or not version:
            self.log("App name, package name, and version are required.")
            return
        if not arch_files:
            self.log("Select at least one architecture (check its box and pick an APK file).")
            return
        if not token:
            self.log("Enter a GitHub token with 'Contents: Read and write' on this repo.")
            return

        if self.remember_var.get():
            save_token(token)

        self.action_button.config(state="disabled")
        self.log_text.delete("1.0", END)
        threading.Thread(
            target=self.run,
            args=(token, app_id, name, package_name, version, description, arch_files, self.icon_asset),
            daemon=True
        ).start()

    def run(self, token, app_id, name, package_name, version, description, arch_files, icon_asset):
        try:
            if icon_asset is None:
                first_apk = next(iter(arch_files.values()))
                self.log(f"Extracting launcher icon from {Path(first_apk).name}...")
                icon_asset = extract_apk_icon(first_apk)
            publish_app(
                token, app_id, name, package_name, version, description,
                arch_files, icon_asset, self.log
            )
            self.log("\nDone.")
        except Exception as e:
            self.log(f"\nFAILED: {e}")
        finally:
            self.root.after(0, lambda: self.action_button.config(state="normal"))


if __name__ == "__main__":
    if sys.platform != "win32":
        print("This tool targets Windows, but will still work on other desktop platforms.")
    root = Tk()
    AdminPanel(root)
    root.mainloop()
