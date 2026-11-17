#!/usr/bin/env python3
"""Writes assets/<device>-api<N>/meta.json for one emulator run.

The GitHub Pages site (site/build.mjs) reads these files to build the test-results matrix and to
label the screenshot gallery. JUnit XML from Gradle's connected tests is summarised into totals and
a per-test list. Only the Python standard library is used.
"""
import argparse
import datetime
import json
import os
import sys
import xml.etree.ElementTree as ET

MEDIA_IMAGE = (".png", ".jpg", ".jpeg", ".webp")
MEDIA_VIDEO = (".mp4", ".webm")


def parse_results(root_dir):
    """Returns (totals, cases) from every TEST-*.xml under root_dir."""
    totals = {"total": 0, "passed": 0, "failed": 0, "skipped": 0}
    cases = []
    if not os.path.isdir(root_dir):
        return totals, cases
    for base, _dirs, files in os.walk(root_dir):
        for name in files:
            if not (name.startswith("TEST-") and name.endswith(".xml")):
                continue
            try:
                tree = ET.parse(os.path.join(base, name))
            except ET.ParseError:
                continue
            for case in tree.iter("testcase"):
                status = "passed"
                message = ""
                if case.find("failure") is not None or case.find("error") is not None:
                    status = "failed"
                    node = case.find("failure") if case.find("failure") is not None else case.find("error")
                    lines = (node.text or "").strip().splitlines()
                    message = node.get("message") or (lines[0] if lines else "")
                elif case.find("skipped") is not None:
                    status = "skipped"
                totals["total"] += 1
                totals[status] += 1
                cases.append({
                    "class": case.get("classname", ""),
                    "name": case.get("name", ""),
                    "status": status,
                    "time": float(case.get("time") or 0),
                    "message": message[:300],
                })
    return totals, cases


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--device", required=True)
    ap.add_argument("--api", type=int, required=True)
    ap.add_argument("--orientation", default="portrait")
    ap.add_argument("--profile", default="")
    ap.add_argument("--target", default="")
    ap.add_argument("--exit-code", type=int, default=0)
    ap.add_argument("--results", default="")
    a = ap.parse_args()

    totals, cases = parse_results(a.results)
    files = sorted(os.listdir(a.out)) if os.path.isdir(a.out) else []
    run_id = os.path.basename(os.path.normpath(a.out))
    meta = {
        "id": run_id,
        "device": a.device,
        "api": a.api,
        "orientation": a.orientation,
        "profile": a.profile,
        "target": a.target,
        "source": "emulator",
        "generated": datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"),
        "commit": os.environ.get("GITHUB_SHA", ""),
        "run": os.environ.get("GITHUB_RUN_ID", ""),
        "status": "passed" if a.exit_code == 0 and totals["failed"] == 0 else "failed",
        "tests": totals,
        "cases": cases,
        "screenshots": [f for f in files if f.lower().endswith(MEDIA_IMAGE)],
        "videos": [f for f in files if f.lower().endswith(MEDIA_VIDEO)],
    }
    with open(os.path.join(a.out, "meta.json"), "w", encoding="utf-8") as fh:
        json.dump(meta, fh, indent=2)
    print(f"meta.json: {meta['status']} {totals} {len(meta['screenshots'])} screenshots {len(meta['videos'])} videos")
    return 0


if __name__ == "__main__":
    sys.exit(main())
