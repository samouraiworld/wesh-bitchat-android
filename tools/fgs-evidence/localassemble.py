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

from capture import CAPTURE_SOURCE, PREFLIGHT_STEPS, SCENARIO_RESULT, SCENARIO_STEPS
from prepare import APK, FILES, SOURCE, digest

RECORDINGS = ("recording-a.webm", "recording-b.webm")
HASHED_INPUTS = {"provenance.json", "attempt.json", *RECORDINGS}
PREFLIGHT_EVENTS = PREFLIGHT_STEPS
EVENTS = SCENARIO_STEPS
OUTPUT_WIDTH = 1440
SCREEN_HEIGHT = 1280
FOOTER_HEIGHT = 80
OUTPUT_HEIGHT = SCREEN_HEIGHT + FOOTER_HEIGHT
CAPTION_LINES = (
    "Wesh 109 - two emulators - simulated BLE netsim",
    "Canonical APK payload - disposable test signature",
)
CAPTION_RECEIPT = (
    "Two emulators; simulated BLE netsim; canonical109 payload; disposable test signature"
)
ATTEMPT_FIELDS = frozenset({
    "result", "failureGate", "preflightEvents", "events", "environment", "emulator",
    "systemImageRevision", "evidenceSource", "captureSource", "notPhysicalEvidence",
    "notPlaySigned", "recordingsExported", "finalVideoQualified", "failureElapsedSeconds",
    "deferredUiDumpCleanupCountBySide",
})


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


def event_times(receipt, key, expected, sequence_gate, clock_gate, *, starts_at_zero=False,
                strictly_increasing=False):
    events = receipt.get(key)
    require(isinstance(events, list) and len(events) == len(expected) and
            all(isinstance(event, dict) and set(event) == {"step", "elapsedSeconds"}
                for event in events), sequence_gate)
    require([event["step"] for event in events] == list(expected), sequence_gate)
    times = [event["elapsedSeconds"] for event in events]
    require(all(type(value) in (int, float) and math.isfinite(value) and value >= 0
                for value in times) and times == sorted(times) and
            (not starts_at_zero or times[0] == 0) and
            (not strictly_increasing or
             all(previous < current for previous, current in zip(times, times[1:]))),
            clock_gate)
    return times


def validate_cleanup_diagnostics(attempt):
    counters = attempt.get("deferredUiDumpCleanupCountBySide")
    require(isinstance(counters, dict) and set(counters) == {"a", "b"} and
            all(type(value) is int and value >= 0 for value in counters.values()),
            "cleanup_diagnostics")
    return counters


def validate_attempt_shape(attempt):
    # A closed receipt schema prevents paths, raw UI dumps, or other incidental runner data
    # from entering the locally published sidecar.
    require(isinstance(attempt, dict) and set(attempt) == ATTEMPT_FIELDS,
            "scenario_receipt_fields")
    failure_elapsed = attempt.get("failureElapsedSeconds")
    require(failure_elapsed is None or
            (type(failure_elapsed) in (int, float) and math.isfinite(failure_elapsed) and
             failure_elapsed >= 0), "failure_elapsed_clock")
    return validate_cleanup_diagnostics(attempt)


def validate_bundle(evidence, source_head):
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
    validate_attempt_shape(attempt)
    require(attempt["evidenceSource"] == source_head and attempt["result"] == SCENARIO_RESULT and
            attempt["recordingsExported"] is True and attempt["finalVideoQualified"] is False and
            attempt["notPhysicalEvidence"] is True and attempt["notPlaySigned"] is True and
            attempt.get("failureGate") is None and attempt.get("failureElapsedSeconds") is None and
            attempt.get("captureSource") == CAPTURE_SOURCE,
            "scenario_receipt")
    event_times(attempt, "preflightEvents", PREFLIGHT_EVENTS,
                "preflight_event_sequence", "preflight_event_clock")
    times = event_times(attempt, "events", EVENTS,
                        "scenario_event_sequence", "scenario_event_clock", starts_at_zero=True,
                        strictly_increasing=True)
    require(all(value < 165 for value in times), "scenario_event_clock")
    return times[-1], checksums, attempt


def validate_inputs(evidence, source_head):
    last_quit, checksums, _ = validate_bundle(evidence, source_head)
    return last_quit, checksums


def run_tool(binary, *args, timeout=30):
    result = subprocess.run([binary, *args], capture_output=True, timeout=timeout)
    require(result.returncode == 0, "local_media_tool_failed")
    return result.stdout.decode(errors="strict")


def probe_duration(ffprobe, path):
    output = run_tool(ffprobe, "-v", "error", "-show_entries", "format=duration", "-of", "json", str(path))
    duration = float(json.loads(output)["format"]["duration"])
    require(math.isfinite(duration) and duration > 0, "recording_duration_invalid")
    return duration


def probe_video(ffprobe, path, *, assembled=False):
    output = run_tool(
        ffprobe, "-v", "error", "-count_frames", "-show_entries",
        "stream=codec_type,codec_name,width,height,pix_fmt,nb_read_frames:"
        "format=format_name,duration",
        "-of", "json", str(path),
    )
    metadata = json.loads(output)
    streams = metadata.get("streams")
    require(isinstance(streams, list) and len(streams) == 1, "video_stream_inventory")
    video = streams[0]
    allowed_codecs = {"h264"} if assembled else {"vp8", "vp9"}
    require(video.get("codec_type") == "video" and video.get("codec_name") in allowed_codecs,
            "video_codec_invalid")
    width, height = video.get("width"), video.get("height")
    require(type(width) is int and type(height) is int and width > 0 and height > 0,
            "video_dimensions_invalid")
    decoded_frames = video.get("nb_read_frames")
    require(isinstance(decoded_frames, str) and decoded_frames.isdigit() and
            int(decoded_frames) > 0, "decoded_frame_inventory_invalid")
    format_name = metadata.get("format", {}).get("format_name")
    require(isinstance(format_name, str) and
            ((assembled and "mp4" in format_name.split(",")) or
             (not assembled and "webm" in format_name.split(","))),
            "video_container_invalid")
    if assembled:
        require((width, height) == (OUTPUT_WIDTH, OUTPUT_HEIGHT) and
                video.get("pix_fmt") == "yuv420p", "assembled_video_format_invalid")
    duration = float(metadata.get("format", {}).get("duration", "nan"))
    require(math.isfinite(duration) and duration > 0, "recording_duration_invalid")
    return {
        "codec": video["codec_name"],
        "container": format_name,
        "pixelFormat": video.get("pix_fmt"),
        "width": width,
        "height": height,
        "decodedFrames": int(decoded_frames),
        "durationSeconds": duration,
    }


def fully_decode(ffmpeg, path):
    run_tool(ffmpeg, "-v", "error", "-xerror", "-i", str(path),
             "-map", "0:v:0", "-f", "null", "-", timeout=240)


def render_footer(path, font_path):
    try:
        from PIL import Image, ImageDraw, ImageFont
    except ImportError as error:
        raise ValidationFailure("local_pillow_unavailable") from error
    try:
        font = ImageFont.truetype(str(font_path), 24)
    except OSError as error:
        raise ValidationFailure("font_not_supported") from error
    image = Image.new("RGB", (OUTPUT_WIDTH, FOOTER_HEIGHT), "black")
    draw = ImageDraw.Draw(image)
    positions = ((12, 4), (12, 42))
    for text, position in zip(CAPTION_LINES, positions):
        bounds = draw.textbbox(position, text, font=font)
        require(bounds[0] >= 0 and bounds[1] >= 0 and
                bounds[2] <= OUTPUT_WIDTH and bounds[3] <= FOOTER_HEIGHT,
                "footer_text_does_not_fit")
        draw.text(position, text, fill="white", font=font)
    image.save(path, format="PNG", optimize=False, compress_level=9)


def composition_filter():
    # The footer occupies only the new 80-pixel band below the two product screens.
    return (
        f"[0:v]setpts=PTS-STARTPTS,scale=720:{SCREEN_HEIGHT}:"
        f"force_original_aspect_ratio=decrease,pad=720:{SCREEN_HEIGHT}:"
        "(ow-iw)/2:(oh-ih)/2:black,setsar=1[a];"
        f"[1:v]setpts=PTS-STARTPTS,scale=720:{SCREEN_HEIGHT}:"
        f"force_original_aspect_ratio=decrease,pad=720:{SCREEN_HEIGHT}:"
        "(ow-iw)/2:(oh-ih)/2:black,setsar=1[b];"
        f"[a][b]hstack=shortest=1,pad={OUTPUT_WIDTH}:{OUTPUT_HEIGHT}:0:0:black[base];"
        f"[2:v]format=rgba[footer];"
        f"[base][footer]overlay=0:{SCREEN_HEIGHT}:eof_action=repeat:shortest=1[out]"
    )


def publish_output(temporary, output, receipt_temporary, sidecar):
    os.link(temporary, output)
    try:
        os.link(receipt_temporary, sidecar)
    except Exception:
        # This process created the output link. A concurrently created sidecar is never removed.
        output.unlink()
        raise


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
    last_quit, inputs, attempt = validate_bundle(evidence, source_head)
    cleanup_counts = validate_cleanup_diagnostics(attempt)
    input_media = [probe_video(ffprobe, evidence/name) for name in RECORDINGS]
    durations = [media["durationSeconds"] for media in input_media]
    # The clock began before either OS recorder command: retain that conservative strict check.
    require_coverage(durations, last_quit)
    for name in RECORDINGS:
        fully_decode(ffmpeg, evidence/name)
    with tempfile.NamedTemporaryFile(dir=output.parent, suffix=".mp4", delete=False) as stream:
        temporary = Path(stream.name)
    with tempfile.NamedTemporaryFile(dir=output.parent, suffix=".png", delete=False) as stream:
        footer = Path(stream.name)
    receipt_temporary = None
    try:
        render_footer(footer, font)
        run_tool(ffmpeg, "-v", "error", "-y", "-xerror", "-i", str(evidence/RECORDINGS[0]),
                 "-i", str(evidence/RECORDINGS[1]), "-loop", "1", "-i", str(footer),
                 "-filter_complex", composition_filter(), "-map", "[out]",
                 "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-pix_fmt", "yuv420p",
                 "-an", "-map_metadata", "-1",
                 str(temporary), timeout=240)
        fully_decode(ffmpeg, temporary)
        output_media = probe_video(ffprobe, temporary, assembled=True)
        output_duration = output_media["durationSeconds"]
        require(output_duration >= last_quit, "assembled_video_does_not_cover_quit")
        receipt = {
            "status": "DURATION_AND_ASSEMBLY_PASS_AWAIT_VISUAL_REVIEW",
            "captureSource": CAPTURE_SOURCE, "controllerSource": source_head,
            "canonicalSource": SOURCE,
            "inputsSHA256": inputs, "fontSHA256": digest(font), "footerSHA256": digest(footer),
            "recordingDurationsSeconds": durations, "lastQuitElapsedSeconds": last_quit,
            "deferredUiDumpCleanupCountBySide": cleanup_counts,
            "inputMedia": input_media, "outputMedia": output_media,
            "outputDurationSeconds": output_duration, "outputSHA256": digest(temporary),
            "physicalEvidence": False, "googleApproved": False, "visualReviewCompleted": False,
            "caption": CAPTION_RECEIPT,
        }
        # No replacement of an existing local video; only qualified duration/assembly gets a final name.
        with tempfile.NamedTemporaryFile(
                dir=output.parent, suffix=".json", mode="w", encoding="utf-8", delete=False) as stream:
            receipt_temporary = Path(stream.name)
            json.dump(receipt, stream, indent=2)
            stream.write("\n")
        publish_output(temporary, output, receipt_temporary, sidecar)
    finally:
        temporary.unlink(missing_ok=True)
        footer.unlink(missing_ok=True)
        if receipt_temporary is not None:
            receipt_temporary.unlink(missing_ok=True)


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
