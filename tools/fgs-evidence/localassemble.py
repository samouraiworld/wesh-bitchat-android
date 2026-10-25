#!/usr/bin/env python3
"""LOCAL ONLY: validate owned synthetic recordings, then assemble their honest footer."""
import argparse
import json
import math
import os
from pathlib import Path
import re
import subprocess
import tempfile

from capture import SCENARIO_RESULT
from prepare import APK, FILES, SOURCE, digest

RECORDINGS = ("recording-a.mp4", "recording-b.mp4")
HASHED_INPUTS = {"provenance.json", "attempt.json", *RECORDINGS}
EVENTS = (
    "actual_onboarding_permissions_completed", "both_apps_display_real_peer",
    "public_messages_received_in_both_directions", "background_connected_device_notification_visible",
    "public_message_sent_while_other_app_in_background", "public_message_visible_after_resume_via_fgs_notification",
    "quit_action_stopped_service_and_removed_notification",
)


class ValidationFailure(ValueError):
    pass


def require(condition, gate):
    if not condition:
        raise ValidationFailure(gate)


def unique_json(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "duplicate_json_key")
            result[key] = value
        return result
    return json.loads(path.read_text(), object_pairs_hook=unique)


def validate_inputs(evidence, source_head):
    require(re.fullmatch(r"[0-9a-f]{40}", source_head) is not None, "controller_source_invalid")
    require({p.name for p in evidence.iterdir()} == HASHED_INPUTS | {"SHA256SUMS"}, "recording_inventory")
    require(all(p.is_file() and not p.is_symlink() for p in evidence.iterdir()), "recording_file_type")
    checksums = {}
    for line in (evidence/"SHA256SUMS").read_text().splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  ([a-zA-Z0-9.-]+)", line)
        require(match is not None, "checksum_manifest_format")
        checksum, name = match.groups()
        require(name not in checksums and name in HASHED_INPUTS, "checksum_manifest_inventory")
        checksums[name] = checksum
    require(set(checksums) == HASHED_INPUTS, "checksum_manifest_inventory")
    require(all(digest(evidence/name) == value for name, value in checksums.items()), "input_checksum")
    provenance, attempt = (unique_json(evidence/name) for name in ("provenance.json", "attempt.json"))
    require(provenance["source"] == SOURCE and provenance["unsignedApkSHA256"] == FILES[APK] and
            provenance["attestationsVerified"] == 11 and provenance["apkPayloadUnchanged"] is True,
            "canonical_provenance")
    require(attempt["evidenceSource"] == source_head and attempt["result"] == SCENARIO_RESULT and
            attempt["recordingsExported"] is True and attempt["finalVideoQualified"] is False and
            attempt["notPhysicalEvidence"] is True and attempt["notPlaySigned"] is True,
            "scenario_receipt")
    require([event["step"] for event in attempt["events"]] == list(EVENTS), "scenario_event_sequence")
    times = [event["elapsedSeconds"] for event in attempt["events"]]
    require(all(type(value) in (int, float) and math.isfinite(value) and 0 <= value < 165
                for value in times) and times == sorted(times), "scenario_event_clock")
    return times[-1], checksums


def run_tool(binary, *args, timeout=30):
    result = subprocess.run([binary, *args], capture_output=True, timeout=timeout)
    require(result.returncode == 0, "local_media_tool_failed")
    return result.stdout.decode(errors="strict")


def probe_duration(ffprobe, path):
    output = run_tool(ffprobe, "-v", "error", "-show_entries", "format=duration", "-of", "json", str(path))
    duration = float(json.loads(output)["format"]["duration"])
    require(math.isfinite(duration) and duration > 0, "recording_duration_invalid")
    return duration


def require_coverage(durations, last_quit):
    require(len(durations) == 2 and all(math.isfinite(value) and value >= last_quit for value in durations),
            "recording_does_not_cover_quit")


def assemble(evidence, output, source_head, ffmpeg, ffprobe, font):
    require(os.environ.get("GITHUB_ACTIONS") != "true", "local_validation_only")
    evidence, output, font = evidence.resolve(), output.resolve(), font.resolve()
    require(output.suffix == ".mp4" and output.parent.is_dir() and not output.is_relative_to(evidence),
            "local_output_location")
    sidecar = output.with_suffix(".json")
    require(not output.exists() and not sidecar.exists(), "local_output_already_exists")
    require(font.is_file() and re.fullmatch(r"[A-Za-z0-9_ /.-]+", str(font)) is not None,
            "font_path_not_supported")
    last_quit, inputs = validate_inputs(evidence, source_head)
    durations = [probe_duration(ffprobe, evidence/name) for name in RECORDINGS]
    # The clock began before either OS recorder command: retain that conservative strict check.
    require_coverage(durations, last_quit)
    with tempfile.NamedTemporaryFile(dir=output.parent, suffix=".mp4", delete=False) as stream:
        temporary = Path(stream.name)
    try:
        overlay = ("[0:v]setpts=PTS-STARTPTS,scale=720:1280[a];"
                   "[1:v]setpts=PTS-STARTPTS,scale=720:1280[b];"
                   "[a][b]hstack=shortest=1,pad=1440:1360:0:0:black,"
                   f"drawtext=fontfile='{font}':"
                   "text='Wesh 107 - two emulators - simulated BLE netsim':fontcolor=white:fontsize=24:x=12:y=1290,"
                   f"drawtext=fontfile='{font}':"
                   "text='Canonical APK payload - disposable test signature':fontcolor=white:fontsize=24:x=12:y=1328")
        run_tool(ffmpeg, "-v", "error", "-y", "-xerror", "-i", str(evidence/RECORDINGS[0]),
                 "-i", str(evidence/RECORDINGS[1]), "-filter_complex", overlay,
                 "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-an", "-map_metadata", "-1",
                 str(temporary), timeout=240)
        output_duration = probe_duration(ffprobe, temporary)
        require(output_duration >= last_quit, "assembled_video_does_not_cover_quit")
        receipt = {
            "status": "DURATION_AND_ASSEMBLY_PASS_AWAIT_VISUAL_REVIEW",
            "captureSource": source_head, "canonicalSource": SOURCE,
            "inputsSHA256": inputs, "fontSHA256": digest(font),
            "recordingDurationsSeconds": durations, "lastQuitElapsedSeconds": last_quit,
            "outputDurationSeconds": output_duration, "outputSHA256": digest(temporary),
            "physicalEvidence": False, "googleApproved": False, "visualReviewCompleted": False,
            "caption": "Two emulators; simulated BLE netsim; canonical107 payload; disposable test signature",
        }
        # No replacement of an existing local video; only qualified duration/assembly gets a final name.
        os.link(temporary, output)
        try:
            with sidecar.open("x") as stream:
                json.dump(receipt, stream, indent=2)
                stream.write("\n")
        except Exception:
            output.unlink()
            raise
    finally:
        temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", required=True, type=Path)
    parser.add_argument("--source-head", required=True)
    parser.add_argument("--ffmpeg", required=True)
    parser.add_argument("--ffprobe", required=True)
    parser.add_argument("--font", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        assemble(args.evidence, args.output, args.source_head, args.ffmpeg, args.ffprobe, args.font)
    except ValidationFailure as error:
        parser.exit(1, f"Local validation failed: {error}\n")
    except Exception:
        parser.exit(1, "Local validation failed; no qualified video exported\n")
