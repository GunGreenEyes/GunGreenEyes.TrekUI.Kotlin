#!/usr/bin/env python3
"""Publish the project's OWN sounds to the sound update channel on mindattic.com (TUK-T21 / LCA-T72).

The launcher checks <SFX_BASE_URL>manifest.json (CoreConfig.SFX_BASE_URL is the one place the address is
written; this script reads it from there) and downloads any owned sound whose SHA-256 differs from what
the app bundles, so a sound can be added or fixed without an app update. See docs/SOUNDS.md.

HARD RULE: only audio the project owns is uploaded. The allowlist is exactly
  - core/src/main/assets/sounds/synth/   (the synthesized set from tools/sounds/synth/make_synth.py)
  - tools/sounds/owned/                  (self-made or properly licensed audio only, see its README)
Anything else is refused: a file outside those folders, a symlink, an unexpected type, an id that is a
show-sound id of the bundled manifest, or a file whose SHA-256 equals any third-party original listed in
the bundled manifest. The show recordings stay user-downloaded from their original public sources.

    python -I tools/sounds/publish-sfx.py --dry-run     # shows what would be published, changes nothing
    python -I tools/sounds/publish-sfx.py               # uploads the sounds, then manifest.json, then verifies

Upload: FTPS (explicit TLS, PROT P) with the credentials in %APPDATA%\\MindAttic\\Ftp\\ftp.json (keys host,
port, user, password, optional servername for the certificate check). The password is never printed,
logged or written anywhere. Sounds go up first and manifest.json last (as a temp name, then renamed), so
the app never sees a manifest that points at a missing file. Afterwards the script GETs the public
manifest and one sound over HTTPS and checks their SHA-256, which proves the folder is publicly served;
on HTTP 403 it adds a minimal .htaccess allowing reads and says so.
"""
import argparse
import datetime
import ftplib
import hashlib
import io
import json
import os
import re
import ssl
import sys
import urllib.error
import urllib.request

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SYNTH_DIRS = ["core/src/main/assets/sounds/synth", "app/src/main/assets/sounds/synth"]
OWNED_DIR = "tools/sounds/owned"
BUNDLED_MANIFESTS = ["core/src/main/assets/sounds/manifest.json", "app/src/main/assets/sounds/manifest.json"]
CORE_CONFIGS = [
    "core/src/main/kotlin/com/gungreeneyes/trekui/core/CoreConfig.kt",
    "core/src/main/java/com/gungreeneyes/trekui/core/CoreConfig.java",
]
REMOTE_DIR = "/mindattic.com/gungreeneyes/trekui/sfx"
FTP_JSON = os.path.join(os.environ.get("APPDATA", ""), "MindAttic", "Ftp", "ftp.json")
AUDIO_EXT = {".ogg", ".wav", ".mp3", ".m4a", ".flac"}
VALID_ID = re.compile(r"[a-z0-9_]{1,80}")
MAX_SOUND_BYTES = 5 * 1024 * 1024  # the app's cap per sound (SoundUpdate.MAX_SOUND_BYTES)
MAX_MANIFEST_BYTES = 2 * 1024 * 1024
USER_AGENT = "TrekUI publish-sfx"
HTACCESS = (
    "# TrekUI sound update channel: public read of the owned sounds and their manifest.\n"
    "Options -Indexes\n"
    "<IfModule mod_authz_core.c>\n  Require all granted\n</IfModule>\n"
    "AddType audio/ogg .ogg\n"
    "AddType application/json .json\n"
)


def fail(msg):
    print("REFUSED: " + msg, file=sys.stderr)
    sys.exit(2)


def first_existing(paths):
    for p in paths:
        full = os.path.join(REPO, p)
        if os.path.exists(full):
            return full
    return None


def base_url():
    path = first_existing(CORE_CONFIGS)
    if not path:
        fail("CoreConfig not found; it holds SFX_BASE_URL")
    with open(path, encoding="utf-8") as f:
        m = re.search(r'SFX_BASE_URL\s*=\s*"(https://[^"]+/)"', f.read())
    if not m:
        fail("SFX_BASE_URL not found in " + path)
    return m.group(1)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def third_party_facts():
    """Ids and original SHA-256s of the show sounds in the bundled manifest (never to be uploaded)."""
    path = first_existing(BUNDLED_MANIFESTS)
    ids, shas = set(), set()
    if not path:
        return ids, shas
    with open(path, encoding="utf-8") as f:
        doc = json.load(f)
    for s in doc.get("sounds", []):
        ids.add(s.get("id", ""))
        for src in [s.get("source") or {}] + list(s.get("alternates") or []) + list(s.get("similar") or []):
            if isinstance(src, dict) and src.get("sha256"):
                shas.add(src["sha256"].lower())
    return ids, shas


def allowlisted_roots():
    roots = []
    synth = first_existing(SYNTH_DIRS)
    if synth:
        roots.append(("SYNTHESIZED", os.path.realpath(synth)))
    owned = os.path.join(REPO, OWNED_DIR)
    if os.path.isdir(owned):
        roots.append(("OWNED", os.path.realpath(owned)))
    return roots


def check_allowlisted(path, roots):
    """Refuses any file that is not a regular audio file directly inside an allowlisted folder."""
    real = os.path.realpath(path)
    if os.path.islink(path) or not os.path.isfile(real):
        fail(path + " is not a regular file")
    if not any(os.path.dirname(real) == root for _, root in roots):
        fail(path + " is outside the allowlist (" + ", ".join(r for _, r in roots) + ")")
    if os.path.splitext(real)[1].lower() not in AUDIO_EXT:
        fail(path + " is not an audio file type the app can play")
    return real


def collect():
    roots = allowlisted_roots()
    if not roots:
        fail("no allowlisted folder exists")
    show_ids, show_shas = third_party_facts()
    files, seen = [], set()
    for group, root in roots:
        for name in sorted(os.listdir(root)):
            path = os.path.join(root, name)
            stem, ext = os.path.splitext(name)
            if ext.lower() not in AUDIO_EXT:
                continue  # README.md and the like
            real = check_allowlisted(path, roots)
            if not VALID_ID.fullmatch(stem):
                fail(name + ": the file name is the sound id and may use only a-z, 0-9 and _")
            if stem in seen:
                fail(name + ": duplicate sound id")
            if stem in show_ids:
                fail(name + ": '" + stem + "' is a show-sound id of the bundled manifest")
            with open(real, "rb") as f:
                data = f.read()
            if len(data) > MAX_SOUND_BYTES:
                fail(name + " is larger than the app accepts (5 MB)")
            digest = sha256(data)
            if digest in show_shas:
                fail(name + " is byte-identical to a third-party show recording")
            seen.add(stem)
            files.append({"id": stem, "name": name, "group": group, "path": real, "data": data, "sha256": digest})
    if not files:
        fail("nothing to publish")
    return files


def fetch(url, limit):
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=30) as r:
        data = r.read(limit + 1)
    if len(data) > limit:
        raise IOError("reply too large")
    return data


def live_version(base):
    try:
        return int(json.loads(fetch(base + "manifest.json", MAX_MANIFEST_BYTES)).get("version", 0))
    except (urllib.error.URLError, IOError, ValueError, AttributeError):
        return 0


def build_manifest(files, base, previous):
    now = datetime.datetime.now(datetime.timezone.utc)
    version = max(int(now.strftime("%Y%m%d%H%M")), previous + 1)
    sounds = []
    for f in files:
        sounds.append({
            "id": f["id"],
            "label": f["id"].upper().replace("_", " "),
            "group": f["group"],
            "events": [],
            "core": False,
            "available": True,
            "confidence": 1.0,
            "match": "owned",
            "source": {
                "url": base + f["name"], "page": base, "site": "mindattic",
                "sha256": f["sha256"], "bytes": len(f["data"]), "title": f["name"],
            },
            "alternates": [],
            "start": 0, "end": 0, "gain_db": 0, "mono": f["group"] == "SYNTHESIZED",
            "fade_in_ms": 0, "fade_out_ms": 0,
        })
    doc = {
        "format": 1,
        "version": version,
        "generated": now.strftime("%Y-%m-%dT%H:%MZ"),
        "notice": "The project's own sounds (synthesized or self-made). Show recordings are never hosted here; "
                  "the app downloads those from their original public pages on the user's request.",
        "sounds": sounds,
    }
    return version, (json.dumps(doc, indent=1, ensure_ascii=False) + "\n").encode("utf-8")


def connect():
    try:
        with open(FTP_JSON, encoding="utf-8") as f:
            cfg = json.load(f)
    except OSError:
        fail("credentials not found at " + FTP_JSON)
    for key in ("host", "user", "password"):
        if not cfg.get(key):
            fail("ftp.json has no '" + key + "'")
    ftp = ftplib.FTP_TLS(context=ssl.create_default_context(), timeout=60)
    ftp.connect(cfg["host"], int(cfg.get("port") or 21))
    # the certificate names the hosting server, not the IP address in "host"
    ftp.host = cfg.get("servername") or cfg["host"]
    ftp.auth()
    ftp.login(cfg["user"], cfg["password"])
    ftp.prot_p()
    return ftp


def ensure_dir(ftp, path):
    cur = ""
    for part in [p for p in path.split("/") if p]:
        cur += "/" + part
        try:
            ftp.cwd(cur)
        except ftplib.error_perm:
            ftp.mkd(cur)
            print("  created " + cur)
    ftp.cwd(path)


def upload(ftp, name, data):
    ftp.storbinary("STOR " + name, io.BytesIO(data))


def verify(base, files, manifest):
    """GETs the public manifest and one sound and checks their SHA-256. Returns (ok, http_status)."""
    sample = files[0]
    try:
        live = fetch(base + "manifest.json", MAX_MANIFEST_BYTES)
        clip = fetch(base + sample["name"], MAX_SOUND_BYTES)
    except urllib.error.HTTPError as e:
        return False, e.code
    ok = sha256(live) == sha256(manifest) and sha256(clip) == sample["sha256"]
    print("  GET %smanifest.json  sha256 %s" % (base, "matches" if sha256(live) == sha256(manifest) else "DIFFERS"))
    print("  GET %s%s  sha256 %s" % (base, sample["name"], "matches" if sha256(clip) == sample["sha256"] else "DIFFERS"))
    return ok, 200


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--dry-run", action="store_true", help="show what would be published; change nothing")
    args = ap.parse_args()

    base = base_url()
    files = collect()
    version, manifest = build_manifest(files, base, live_version(base))
    total = sum(len(f["data"]) for f in files)
    print("Sound update channel: " + base)
    print("Remote folder:        " + REMOTE_DIR + "/")
    for group in ("SYNTHESIZED", "OWNED"):
        n = [f for f in files if f["group"] == group]
        if n:
            print("  %-12s %3d files" % (group, len(n)))
    print("  %d files, %.1f KB, manifest version %d (%d bytes)" % (len(files), total / 1024.0, version, len(manifest)))
    if args.dry_run:
        for f in files:
            print("    %-28s %s %7d" % (f["name"], f["sha256"][:16], len(f["data"])))
        print("DRY RUN: nothing uploaded.")
        return 0

    ftp = connect()
    try:
        ensure_dir(ftp, REMOTE_DIR)
        for f in files:
            upload(ftp, f["name"], f["data"])
        print("  uploaded %d sounds" % len(files))
        upload(ftp, "manifest.json.tmp", manifest)
        try:
            ftp.delete("manifest.json")
        except ftplib.error_perm:
            pass  # first publish: no manifest yet
        ftp.rename("manifest.json.tmp", "manifest.json")
        print("  uploaded manifest.json (version %d)" % version)

        ok, status = verify(base, files, manifest)
        if status == 403:
            print("  HTTP 403: adding a minimal .htaccess that allows reads")
            upload(ftp, ".htaccess", HTACCESS.encode("ascii"))
            ok, status = verify(base, files, manifest)
    finally:
        try:
            ftp.quit()
        except (ftplib.Error, OSError):
            ftp.close()
    print("VERIFIED: publicly served, checksums match." if ok else "VERIFY FAILED (HTTP %d)" % status)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
