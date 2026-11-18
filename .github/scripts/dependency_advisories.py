#!/usr/bin/env python3
"""Summarise `cargo audit --json` output for the vendored Arti lockfile.

The weekly advisory watch runs this after `cargo audit`. It reports:

- RustSec vulnerabilities and unsound/unmaintained warnings in the lockfile;
- how far the pinned `arti-client` lags the latest release on crates.io
  (Tor security fixes are published as TROVE entries in the Arti changelog,
  which RustSec does not carry, so lagging Arti is a signal of its own).

Modes:
  report  write the summary to stdout and $GITHUB_STEP_SUMMARY only.
  issue   additionally keep exactly one open issue in sync with the summary:
          create or update it while there are findings, close it when clean.

It never fails the job because of findings; only tooling errors fail it.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import urllib.request
from datetime import datetime, timezone

ISSUE_TITLE = "Dependency advisory watch: vendored Arti lockfile"
CRATES_URL = "https://crates.io/api/v1/crates/arti-client"


def pinned_arti(lockfile: str) -> str | None:
    text = open(lockfile, encoding="utf-8").read()
    m = re.search(r'name = "arti-client"\nversion = "([^"]+)"', text)
    return m.group(1) if m else None


def latest_arti() -> str | None:
    req = urllib.request.Request(CRATES_URL, headers={"User-Agent": "wesh-advisory-watch"})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.load(resp)["crate"]["max_stable_version"]
    except Exception as exc:  # network trouble must not hide the audit result
        print(f"warning: could not query crates.io: {exc}", file=sys.stderr)
        return None


def version_tuple(v: str) -> tuple[int, ...]:
    return tuple(int(p) for p in re.findall(r"\d+", v.split("-")[0])[:3])


def build_summary(audit: dict, lockfile: str, pinned: str | None, latest: str | None):
    vulns = audit.get("vulnerabilities", {}).get("list", [])
    warnings = audit.get("warnings", {})
    unsound = warnings.get("unsound", [])
    unmaintained = warnings.get("unmaintained", [])
    lagging = bool(pinned and latest and version_tuple(pinned) < version_tuple(latest))

    lines = [
        f"Lockfile: `{lockfile}`",
        f"Checked: {datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M UTC')}",
        "",
        f"`arti-client` pinned: **{pinned or 'not found'}**; latest on crates.io: **{latest or 'unknown'}**"
        + (" (behind: review the Arti changelog for TROVE security fixes)" if lagging else ""),
        "",
    ]
    if vulns:
        lines += ["### Vulnerabilities", "", "| Advisory | Crate | Version | Patched | Title |", "| --- | --- | --- | --- | --- |"]
        for v in sorted(vulns, key=lambda x: x["advisory"]["id"]):
            a = v["advisory"]
            patched = " or ".join(f"`{r}`" for r in v.get("versions", {}).get("patched", [])) or "none"
            title = a["title"].replace("|", "\\|")
            lines.append(f"| {a['id']} | {v['package']['name']} | {v['package']['version']} | {patched} | {title} |")
        lines.append("")
    for label, items in (("Unsound", unsound), ("Unmaintained", unmaintained)):
        if items:
            ids = sorted({f"{w['advisory']['id']} ({w['package']['name']})" for w in items if w.get("advisory")})
            lines += [f"### {label}", "", ", ".join(ids), ""]
    if not (vulns or unsound or lagging):
        lines.append("No vulnerability, unsound warning or Arti lag found.")
    lines += [
        "",
        "Accepted risks are tracked in the release decision records, not here. A finding listed above is not proof that the code path is reachable in the app; it is a prompt to check.",
    ]
    has_findings = bool(vulns or unsound or lagging)
    # Without both versions the lag is unknown, not absent: never report clean.
    complete = bool(pinned and latest)
    if not complete:
        lines += ["", "**Incomplete check:** the Arti versions could not both be read, so the lag is unknown."]
    return "\n".join(lines), has_findings, complete


def gh(*args: str) -> str:
    return subprocess.run(["gh", *args], check=True, capture_output=True, text=True).stdout


def sync_issue(body: str, has_findings: bool, complete: bool) -> None:
    found = json.loads(gh("issue", "list", "--state", "open", "--search", f'in:title "{ISSUE_TITLE}"',
                          "--json", "number,title", "--limit", "20"))
    existing = next((i["number"] for i in found if i["title"] == ISSUE_TITLE), None)
    if has_findings:
        if existing:
            gh("issue", "edit", str(existing), "--body", body)
            print(f"updated issue #{existing}")
        else:
            print(gh("issue", "create", "--title", ISSUE_TITLE, "--body", body).strip())
    elif existing and not complete:
        print(f"left issue #{existing} open: the check was incomplete")
    elif existing:
        gh("issue", "close", str(existing), "--comment", "Weekly check is clean.\n\n" + body)
        print(f"closed issue #{existing}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lockfile", required=True)
    parser.add_argument("--audit-json", required=True)
    parser.add_argument("--mode", choices=["report", "issue"], default="report")
    args = parser.parse_args()

    with open(args.audit_json, encoding="utf-8") as fh:
        audit = json.load(fh)
    body, has_findings, complete = build_summary(audit, args.lockfile, pinned_arti(args.lockfile), latest_arti())
    print(body)
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as fh:
            fh.write(body + "\n")
    if args.mode == "issue":
        sync_issue(body, has_findings, complete)
    return 0


if __name__ == "__main__":
    sys.exit(main())
