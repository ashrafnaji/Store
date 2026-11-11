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


def _extract_apk_icon_aapt(apk_path: str):
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
    resource_names = {}  # "mipmap/name" -> id
    resource_aliases = {}  # id -> ["mipmap/name", ...] for entries that reference another resource
    current_id = None
    for line in resources.splitlines():
        resource_match = re.match(r"\s*resource (0x[0-9a-fA-F]+)\s+(\S+)", line)
        if resource_match:
            current_id = resource_match.group(1).lower()
            resource_names[resource_match.group(2)] = current_id
            continue
        file_match = re.match(r"\s*\(([^)]*)\) \(file\) (\S+)", line)  # "type=..." suffix is absent in some aapt2 versions
        if current_id and file_match:
            resource_files.setdefault(current_id, []).append((file_match.group(1), file_match.group(2)))
            continue
        alias_match = re.match(r"\s*\([^)]*\) @(?:\+)?(?:[\w.]+:)?(\S+)", line)
        if current_id and alias_match:
            resource_aliases.setdefault(current_id, []).append(alias_match.group(1))

    density_rank = {
        "xxxhdpi": 6, "xxhdpi": 5, "xhdpi": 4, "hdpi": 3, "mdpi": 2, "ldpi": 1,
    }

    def raster_candidates(resource_id, depth=0):
        resource_id = resource_id.lower()
        candidates = []
        for config, resource_path in resource_files.get(resource_id, []):
            lower_path = resource_path.lower()
            if lower_path.endswith(".9.png") or not lower_path.endswith((".png", ".webp", ".jpg", ".jpeg")):
                continue
            rank = max((score for density, score in density_rank.items() if density in config), default=0)
            candidates.append((rank, resource_path))
        # Icons are often aliases (e.g. "() @mipmap/other_icon") -- follow them to the real files.
        if not candidates and depth < 5:
            for target_name in resource_aliases.get(resource_id, []):
                target_id = resource_names.get(target_name)
                if target_id:
                    candidates.extend(raster_candidates(target_id, depth + 1))
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


def _inspect_apk_aapt(apk_path: str):
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


# ---------------------------------------------------------------------------------------------
# Built-in APK reader (pure Python, no Android SDK needed).
#
# Parses the binary AndroidManifest.xml and resources.arsc directly out of the APK zip, so
# auto-fill and icon extraction work on any PC. aapt is only used as a fallback if this parser
# hits something it can't handle.
# ---------------------------------------------------------------------------------------------
import struct

_ATTR_LABEL = 0x01010001
_ATTR_ICON = 0x01010002
_ATTR_VERSION_CODE = 0x0101021B
_ATTR_VERSION_NAME = 0x0101021C
_ATTR_ROUND_ICON = 0x0101052C
_ATTR_DRAWABLE = 0x01010199
_ATTR_SRC = 0x01010119

_TYPE_REFERENCE = 0x01
_TYPE_STRING = 0x03

_DENSITY_SCORE = {120: 1, 160: 2, 213: 2, 240: 3, 320: 4, 480: 5, 640: 6}


def _read_string_pool(data, offset):
    """Parses a ResStringPool chunk at `offset`; returns a list of strings."""
    _type, header_size, _size, count, _styles, flags, strings_start, _ = struct.unpack_from("<HHIIIIII", data, offset)
    utf8 = bool(flags & 0x100)
    offsets = struct.unpack_from(f"<{count}I", data, offset + header_size)
    base = offset + strings_start
    strings = []
    for string_offset in offsets:
        pos = base + string_offset
        try:
            if utf8:
                # char length (skip), then byte length -- each 1 or 2 bytes
                length = data[pos]
                pos += 2 if length & 0x80 else 1
                byte_length = data[pos]
                if byte_length & 0x80:
                    byte_length = ((byte_length & 0x7F) << 8) | data[pos + 1]
                    pos += 2
                else:
                    pos += 1
                strings.append(data[pos:pos + byte_length].decode("utf-8", errors="replace"))
            else:
                length = struct.unpack_from("<H", data, pos)[0]
                pos += 2
                if length & 0x8000:
                    length = ((length & 0x7FFF) << 16) | struct.unpack_from("<H", data, pos)[0]
                    pos += 2
                strings.append(data[pos:pos + length * 2].decode("utf-16-le", errors="replace"))
        except Exception:
            strings.append("")
    return strings


def _parse_axml(data):
    """
    Parses a compiled (binary) Android XML file. Returns a list of elements in document order:
    [{"name": tag, "depth": n, "attrs": [(resource_id_or_0, attr_name, (data_type, data, raw_string))]}]
    """
    strings = []
    resource_ids = []
    elements = []
    depth = 0
    _type, header_size, total = struct.unpack_from("<HHI", data, 0)
    pos = header_size
    end = min(total, len(data))
    while pos + 8 <= end:
        chunk_type, chunk_header, chunk_size = struct.unpack_from("<HHI", data, pos)
        if chunk_size < 8:
            break
        if chunk_type == 0x0001:
            strings = _read_string_pool(data, pos)
        elif chunk_type == 0x0180:
            count = (chunk_size - chunk_header) // 4
            resource_ids = list(struct.unpack_from(f"<{count}I", data, pos + chunk_header))
        elif chunk_type == 0x0102:
            name_index, attr_start, attr_size, attr_count = struct.unpack_from("<IHHH", data, pos + 20)
            attr_size = attr_size or 20
            attrs = []
            attr_pos = pos + 16 + attr_start
            for i in range(attr_count):
                a = attr_pos + i * attr_size
                _ns, a_name, a_raw, _vsize, _res0, v_type, v_data = struct.unpack_from("<IIIHBBI", data, a)
                res_id = resource_ids[a_name] if a_name < len(resource_ids) else 0
                name = strings[a_name] if a_name < len(strings) else ""
                raw = strings[a_raw] if a_raw != 0xFFFFFFFF and a_raw < len(strings) else None
                attrs.append((res_id, name, (v_type, v_data, raw)))
            tag = strings[name_index] if name_index < len(strings) else ""
            elements.append({"name": tag, "depth": depth, "attrs": attrs})
            depth += 1
        elif chunk_type == 0x0103:
            depth -= 1
        pos += chunk_size
    return elements


def _find_attr(element, resource_id, fallback_name=None):
    for res_id, name, value in element["attrs"]:
        if res_id == resource_id or (fallback_name and res_id == 0 and name == fallback_name):
            return value
    return None


class _ResourceTable:
    """Minimal resources.arsc reader: maps resource id -> [(config_info, data_type, data)]."""

    def __init__(self, data):
        self.entries = {}
        self.global_strings = []
        _type, header_size, total, _pkg_count = struct.unpack_from("<HHII", data, 0)
        pos = header_size
        end = min(total, len(data))
        while pos + 8 <= end:
            chunk_type, _chunk_header, chunk_size = struct.unpack_from("<HHI", data, pos)
            if chunk_size < 8:
                break
            if chunk_type == 0x0001:
                self.global_strings = _read_string_pool(data, pos)
            elif chunk_type == 0x0200:
                self._read_package(data, pos, chunk_size)
            pos += chunk_size

    def _read_package(self, data, start, size):
        header_size = struct.unpack_from("<H", data, start + 2)[0]
        package_id = struct.unpack_from("<I", data, start + 8)[0]
        type_strings_offset = struct.unpack_from("<I", data, start + 268)[0]
        self.type_names = _read_string_pool(data, start + type_strings_offset) if type_strings_offset else []
        pos = start + header_size
        end = start + size
        while pos + 8 <= end:
            chunk_type, chunk_header, chunk_size = struct.unpack_from("<HHI", data, pos)
            if chunk_size < 8:
                break
            if chunk_type == 0x0201:
                self._read_type(data, pos, chunk_header, package_id)
            pos += chunk_size

    def _read_type(self, data, start, header_size, package_id):
        type_id, flags, _reserved, entry_count, entries_start = struct.unpack_from("<BBHII", data, start + 8)
        config_pos = start + 20
        config_size = struct.unpack_from("<I", data, config_pos)[0]
        language = data[config_pos + 8:config_pos + 10].rstrip(b"\0").decode("latin-1", errors="replace")
        density = struct.unpack_from("<H", data, config_pos + 14)[0] if config_size >= 16 else 0
        config = {"language": language, "density": density}

        index_pos = start + header_size
        offsets = []
        if flags & 0x01:  # FLAG_SPARSE: (u16 index, u16 offset/4)
            for i in range(entry_count):
                idx, off = struct.unpack_from("<HH", data, index_pos + i * 4)
                offsets.append((idx, off * 4))
        elif flags & 0x02:  # FLAG_OFFSET16
            for i in range(entry_count):
                off = struct.unpack_from("<H", data, index_pos + i * 2)[0]
                if off != 0xFFFF:
                    offsets.append((i, off * 4))
        else:
            for i in range(entry_count):
                off = struct.unpack_from("<I", data, index_pos + i * 4)[0]
                if off != 0xFFFFFFFF:
                    offsets.append((i, off))

        for index, off in offsets:
            entry_pos = start + entries_start + off
            try:
                entry_size, entry_flags = struct.unpack_from("<HH", data, entry_pos)
                if entry_flags & 0x0008:  # FLAG_COMPACT: data inline, type in high byte of flags
                    v_type = entry_flags >> 8
                    v_data = struct.unpack_from("<I", data, entry_pos + 4)[0]
                elif entry_flags & 0x0001:  # complex (style/array) -- not needed here
                    continue
                else:
                    _vsize, _res0, v_type, v_data = struct.unpack_from("<HBBI", data, entry_pos + entry_size)
            except struct.error:
                continue
            resource_id = (package_id << 24) | (type_id << 16) | index
            self.entries.setdefault(resource_id, []).append((config, v_type, v_data))

    def string(self, index):
        return self.global_strings[index] if 0 <= index < len(self.global_strings) else ""

    def resolve_string(self, resource_id, depth=0):
        values = self.entries.get(resource_id, [])
        # Prefer the default (no-locale) value, then English, then anything.
        ordered = sorted(values, key=lambda v: 0 if not v[0]["language"] else 1 if v[0]["language"] == "en" else 2)
        for _config, v_type, v_data in ordered:
            if v_type == _TYPE_STRING:
                return self.string(v_data)
            if v_type == _TYPE_REFERENCE and depth < 5:
                text = self.resolve_string(v_data, depth + 1)
                if text:
                    return text
        return ""

    def file_paths(self, resource_id, depth=0):
        """Returns [(density_score, path)] for a file resource, following aliases."""
        results = []
        for config, v_type, v_data in self.entries.get(resource_id, []):
            if v_type == _TYPE_STRING:
                density = config["density"]
                score = _DENSITY_SCORE.get(density, 7 if density == 0xFFFF else 0)
                results.append((score, self.string(v_data)))
            elif v_type == _TYPE_REFERENCE and depth < 5:
                results.extend(self.file_paths(v_data, depth + 1))
        return results


def _value_to_text(value, table):
    if value is None:
        return ""
    v_type, v_data, raw = value
    if raw is not None:
        return raw
    if v_type == _TYPE_REFERENCE and table:
        return table.resolve_string(v_data)
    if v_type in (0x10, 0x11):
        return str(v_data)
    return ""


def _load_apk(apk_path):
    with zipfile.ZipFile(apk_path) as apk:
        manifest = _parse_axml(apk.read("AndroidManifest.xml"))
        names = apk.namelist()
        try:
            table = _ResourceTable(apk.read("resources.arsc"))
        except KeyError:
            table = None
    return manifest, table, names


def inspect_apk_builtin(apk_path):
    manifest, table, names = _load_apk(apk_path)
    package_name = version_name = app_label = ""
    for element in manifest:
        if element["name"] == "manifest":
            package_name = _value_to_text(_find_attr(element, 0, "package"), table)
            version_name = _value_to_text(_find_attr(element, _ATTR_VERSION_NAME, "versionName"), table)
        elif element["name"] == "application":
            app_label = _value_to_text(_find_attr(element, _ATTR_LABEL, "label"), table)
            break

    abis = sorted({
        name.split("/")[1] for name in names
        if name.startswith("lib/") and name.endswith(".so") and name.count("/") >= 2
    })
    return {
        "package_name": package_name,
        "version_name": version_name,
        "app_label": app_label,
        "abis": abis,  # empty list == no native libs == works on every architecture
    }


def extract_apk_icon_builtin(apk_path):
    manifest, table, _names = _load_apk(apk_path)
    if table is None:
        return None
    icon_ids = []
    for element in manifest:
        if element["name"] == "application":
            for attr_id in (_ATTR_ICON, _ATTR_ROUND_ICON):
                value = _find_attr(element, attr_id)
                if value and value[0] == _TYPE_REFERENCE:
                    icon_ids.append(value[1])
            break
    # Fall back to the first activity icon if the <application> has none.
    if not icon_ids:
        for element in manifest:
            if element["name"] in ("activity", "activity-alias"):
                value = _find_attr(element, _ATTR_ICON)
                if value and value[0] == _TYPE_REFERENCE:
                    icon_ids.append(value[1])
                    break

    raster_ext = (".png", ".webp", ".jpg", ".jpeg")
    with zipfile.ZipFile(apk_path) as apk:
        seen = set()
        queue = list(icon_ids)
        while queue:
            resource_id = queue.pop(0)
            if resource_id in seen or len(seen) > 20:
                continue
            seen.add(resource_id)
            paths = table.file_paths(resource_id)
            rasters = sorted(
                (p for p in paths if p[1].lower().endswith(raster_ext) and not p[1].lower().endswith(".9.png")),
                reverse=True,
            )
            if rasters:
                resource_path = rasters[0][1]
                extension = Path(resource_path).suffix.lower().lstrip(".")
                return {
                    "data": apk.read(resource_path),
                    "extension": "jpg" if extension == "jpeg" else extension,
                    "resource_path": resource_path,
                    "apk_path": str(Path(apk_path).resolve()),
                }
            # Adaptive / layer-list / bitmap XML: follow foreground first, then other drawables.
            for _score, xml_path in paths:
                if not xml_path.lower().endswith(".xml"):
                    continue
                try:
                    elements = _parse_axml(apk.read(xml_path))
                except Exception:
                    continue
                refs = []
                for element in elements:
                    for attr_id in (_ATTR_DRAWABLE, _ATTR_SRC):
                        value = _find_attr(element, attr_id)
                        if value and value[0] == _TYPE_REFERENCE:
                            refs.append((0 if element["name"] == "foreground" else 1, value[1]))
                queue.extend(ref for _, ref in sorted(refs))
    return None


def inspect_apk(apk_path: str):
    """Reads package/version/label/ABIs. Built-in parser first; aapt only as a fallback."""
    try:
        return inspect_apk_builtin(apk_path)
    except Exception:
        return _inspect_apk_aapt(apk_path)


def extract_apk_icon(apk_path: str):
    """Returns the APK's best raster launcher icon. Built-in parser first; aapt as a fallback."""
    try:
        icon = extract_apk_icon_builtin(apk_path)
        if icon:
            return icon
    except Exception:
        pass
    return _extract_apk_icon_aapt(apk_path)


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
    # versionName is free text (e.g. "Headunit Reloaded V7.2.1") -- keep it as-is for the catalog,
    # which the store compares against the installed versionName, but make the tag URL-safe.
    tag_version = re.sub(r"[^A-Za-z0-9._-]+", "-", version).strip("-.")
    tag = f"{app_id}-v{tag_version}"
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
        suffix = "universal" if set(archs) == set(ARCHS) else "-".join(sorted(archs))
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
        # Pack the button first so a long path can't push it off the edge of the window.
        Button(row, text="Browse...", command=lambda a=arch: self.pick_file(a)).pack(side=RIGHT)
        path_label = Label(row, textvariable=self.arch_vars[arch], anchor="w", fg="gray", width=1)
        path_label.pack(side=LEFT, fill=X, expand=True)

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

        try:
            info = inspect_apk(path)
        except Exception as error:
            self.log(f"Error reading {Path(path).name}: {error}")
            info = None
        if info is None:
            self.log(
                f"Couldn't read {Path(path).name} -- it may be damaged or not a valid APK. "
                "Fill in the fields and pick per-architecture files manually."
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
