"""
Small Windows desktop app (Tkinter, stdlib only) to build the Store APKs and publish
them as a GitHub Release in one click.

Run with:  python tools/release_gui.py

It does NOT need `requests` or any pip package - just a Python 3 install and a JDK/
Android SDK already set up for the Gradle build (same as building from Android Studio).

The GitHub token is only kept in memory unless you check "Remember on this PC", in
which case it's written to %USERPROFILE%\\.store_releaser\\config.json - never into
the git repo.
"""
import json
import mimetypes
import os
import subprocess
import sys
import threading
import urllib.error
import urllib.request
from glob import glob
from pathlib import Path
from tkinter import (
    BOTH, END, LEFT, RIGHT, X, Y, StringVar, BooleanVar,
    Tk, Frame, Label, Entry, Button, Checkbutton, Text, Scrollbar
)

REPO_OWNER = "ashrafnaji"
REPO_NAME = "Store"
CONFIG_PATH = Path.home() / ".store_releaser" / "config.json"


def load_saved_token():
    try:
        return json.loads(CONFIG_PATH.read_text()).get("token", "")
    except Exception:
        return ""


def save_token(token: str):
    CONFIG_PATH.parent.mkdir(parents=True, exist_ok=True)
    CONFIG_PATH.write_text(json.dumps({"token": token}))


def find_gradle() -> str:
    from shutil import which
    found = which("gradle") or which("gradle.bat")
    if found:
        return found
    # Fall back to a Gradle distribution already cached by Android Studio.
    dists_root = Path.home() / ".gradle" / "wrapper" / "dists"
    if dists_root.exists():
        candidates = sorted(dists_root.glob("gradle-*-bin/*/gradle-*/bin/gradle.bat"), reverse=True)
        if candidates:
            return str(candidates[0])
    raise RuntimeError(
        "Could not find a 'gradle' executable. Install Gradle, or open the project "
        "once in Android Studio so it caches one under %USERPROFILE%\\.gradle\\wrapper\\dists."
    )


def build_apks(project_dir: Path, version_name: str, version_code: str, log):
    gradle = find_gradle()
    log(f"Using Gradle: {gradle}")

    env = os.environ.copy()
    env["APP_VERSION_NAME"] = version_name
    env["APP_VERSION_CODE"] = version_code

    cmd = [gradle, "assembleRelease", "--console=plain", "--no-daemon"]
    log("Running: " + " ".join(cmd))

    process = subprocess.Popen(
        cmd, cwd=str(project_dir), env=env,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True
    )
    for line in process.stdout:
        log(line.rstrip())
    process.wait()
    if process.returncode != 0:
        raise RuntimeError(f"Gradle build failed (exit code {process.returncode})")

    apk_dir = project_dir / "app" / "build" / "outputs" / "apk" / "release"
    apks = sorted(glob(str(apk_dir / "*.apk")))
    if not apks:
        raise RuntimeError(f"Build succeeded but no APKs found in {apk_dir}")
    log(f"Built {len(apks)} APK(s):")
    for apk in apks:
        log(f"  - {apk}")
    return apks


def api_request(url, token, method="GET", data=None, content_type="application/json"):
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/vnd.github+json")
    if data is not None:
        req.add_header("Content-Type", content_type)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"GitHub API error {e.code} for {url}: {body}") from None


def create_release_and_upload(token: str, version_name: str, apks, log):
    tag = f"v{version_name}"
    log(f"Creating GitHub release {tag}...")

    payload = json.dumps({
        "tag_name": tag,
        "name": f"Store {tag}",
        "generate_release_notes": True,
    }).encode("utf-8")

    release = api_request(
        f"https://api.github.com/repos/{REPO_OWNER}/{REPO_NAME}/releases",
        token, method="POST", data=payload
    )
    upload_url = release["upload_url"].split("{")[0]
    log(f"Release created: {release['html_url']}")

    for apk_path in apks:
        name = Path(apk_path).name
        mime = mimetypes.guess_type(name)[0] or "application/vnd.android.package-archive"
        log(f"Uploading {name}...")
        data = Path(apk_path).read_bytes()
        api_request(f"{upload_url}?name={name}", token, method="POST", data=data, content_type=mime)
        log(f"  done ({len(data) / 1024:.0f} KB)")

    log("All assets uploaded.")
    log(release["html_url"])


class ReleaserApp:
    def __init__(self, root: Tk):
        self.root = root
        root.title("Store - Release Builder")
        root.geometry("640x480")

        default_project_dir = Path(__file__).resolve().parent.parent

        form = Frame(root, padx=10, pady=10)
        form.pack(fill=X)

        self.project_dir_var = StringVar(value=str(default_project_dir))
        self.version_var = StringVar()
        self.token_var = StringVar(value=load_saved_token())
        self.remember_var = BooleanVar(value=bool(load_saved_token()))

        self._row(form, "Project folder:", self.project_dir_var)
        self._row(form, "Version (e.g. 1.0.1):", self.version_var)
        self._row(form, "GitHub token:", self.token_var, show="*")

        Checkbutton(
            form, text="Remember token on this PC", variable=self.remember_var
        ).pack(anchor="w", pady=(0, 8))

        self.action_button = Button(form, text="Build && Release", command=self.start)
        self.action_button.pack(anchor="w")

        log_frame = Frame(root, padx=10, pady=10)
        log_frame.pack(fill=BOTH, expand=True)
        scrollbar = Scrollbar(log_frame)
        scrollbar.pack(side=RIGHT, fill=Y)
        self.log_text = Text(log_frame, wrap="word", yscrollcommand=scrollbar.set)
        self.log_text.pack(fill=BOTH, expand=True)
        scrollbar.config(command=self.log_text.yview)

    def _row(self, parent, label_text, var, show=None):
        row = Frame(parent)
        row.pack(fill=X, pady=4)
        Label(row, text=label_text, width=20, anchor="w").pack(side=LEFT)
        Entry(row, textvariable=var, show=show).pack(side=LEFT, fill=X, expand=True)

    def log(self, message: str):
        def append():
            self.log_text.insert(END, message + "\n")
            self.log_text.see(END)
        self.root.after(0, append)

    def start(self):
        version = self.version_var.get().strip()
        token = self.token_var.get().strip()
        project_dir = Path(self.project_dir_var.get().strip())

        if not version or not all(part.isdigit() for part in version.split(".")):
            self.log("Enter a numeric version like 1.0.1")
            return
        if not token:
            self.log("Enter a GitHub token with 'Contents: Read and write' on this repo.")
            return

        if self.remember_var.get():
            save_token(token)

        self.action_button.config(state="disabled")
        self.log_text.delete("1.0", END)
        threading.Thread(target=self.run, args=(project_dir, version, token), daemon=True).start()

    def run(self, project_dir: Path, version: str, token: str):
        try:
            version_code = str(int(__import__("time").time()))
            apks = build_apks(project_dir, version, version_code, self.log)
            create_release_and_upload(token, version, apks, self.log)
            self.log("\nDone.")
        except Exception as e:
            self.log(f"\nFAILED: {e}")
        finally:
            self.root.after(0, lambda: self.action_button.config(state="normal"))


if __name__ == "__main__":
    if sys.platform != "win32":
        print("This tool targets Windows, but will still work anywhere Gradle runs.")
    root = Tk()
    ReleaserApp(root)
    root.mainloop()
