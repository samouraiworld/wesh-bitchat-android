#!/usr/bin/env python3
"""Closed, privacy-preserving progress trace shared by evidence controllers."""

import math
from threading import Lock


ONBOARDING_TRACE_EVENTS = frozenset({
    "activity_resumed", "welcome_seen", "start_tapped", "bluetooth", "permission",
    "notifications", "auxiliary", "home", "unknown", "ui_prepare", "ui_stale",
    "ui_command", "ui_output", "ui_read", "ui_parse", "ui_complete", "room_open_tapped",
    "send_requested", "composer_focus_requested", "composer_focused",
    "composer_character_requested", "composer_prefix_confirmed",
    "composer_expected", "composer_empty", "composer_other", "composer_shorter",
    "composer_longer", "composer_same_length_other", "composer_ambiguous",
    "confirmation_visible", "confirmation_tap_requested",
    "confirmation_present_after_tap", "confirmation_dismissed",
    "feedback_destination_changed", "feedback_draft_changed",
    "feedback_send_unavailable", "feedback_send_unconfirmed",
    "feedback_draft_unavailable", "feedback_bluetooth_needed",
    "bubble_candidate_exact", "bubble_candidate_editor_only", "bubble_candidate_absent",
    "failure_caller_deadline_reached", "message_bubble", "remote_received",
    "background_requested", "notification_visible", "notification_open_tapped",
    "activity_resumed_after_notification", "quit_requested", "shade_opened",
    "notification_card_found", "expand_tapped", "quit_action_found", "quit_tapped",
    "service_stopped", "notification_absent", "quit_completed",
    "onboarding_completed", "fgs_active", "peers_visible", "messages_bidirectional",
    "background_send", "scenario_started",
    "screen_home_ready", "screen_home_before_welcome", "screen_welcome",
    "screen_welcome_marker_disabled", "screen_bluetooth_explainer",
    "screen_notifications_explainer", "screen_permission_system",
    "screen_startup_preparing", "screen_startup_blocked", "screen_app_loading",
    "screen_app_error", "screen_bluetooth_fallback", "screen_unknown_app",
    "screen_android_error", "screen_unknown_system", "screen_unknown_foreign",
    "screen_unknown_mixed",
})
MAX_TRACE_EVENTS = 64
TRACE_PREFIX_EVENTS = 8
TRACE_TIMING_LIMIT_SECONDS = 390
TRACE_SIDES = frozenset({"a", "b", "global"})
TRACE_FIELDS = frozenset({"schemaVersion", "events", "counts", "droppedEvents"})
TRACE_RECORD_FIELDS = frozenset({
    "event", "side", "attempt", "allocatedSeconds", "elapsedSeconds",
})


def empty_trace_payload():
    return {
        "schemaVersion": 2,
        "events": [],
        "counts": {event: 0 for event in sorted(ONBOARDING_TRACE_EVENTS)},
        "droppedEvents": 0,
    }


def validate_trace_payload(payload):
    if not isinstance(payload, dict) or set(payload) != TRACE_FIELDS:
        raise ValueError("Invalid closed onboarding trace schema")
    counts = payload.get("counts")
    events = payload.get("events")
    dropped = payload.get("droppedEvents")
    if payload.get("schemaVersion") != 2 or not isinstance(counts, dict) \
            or set(counts) != ONBOARDING_TRACE_EVENTS:
        raise ValueError("Invalid closed onboarding trace enums")
    if (not isinstance(events, list) or len(events) > MAX_TRACE_EVENTS
            or type(dropped) is not int or dropped < 0
            or any(type(value) is not int or value < 0 for value in counts.values())):
        raise ValueError("Invalid closed onboarding trace bounds")
    if sum(counts.values()) != len(events) + dropped:
        raise ValueError("Inconsistent closed onboarding trace counts")
    for record in events:
        if (not isinstance(record, dict) or set(record) != TRACE_RECORD_FIELDS
                or record.get("event") not in ONBOARDING_TRACE_EVENTS
                or record.get("side") not in TRACE_SIDES
                or type(record.get("attempt")) is not int
                or not 0 <= record["attempt"] <= 2):
            raise ValueError("Invalid closed onboarding trace record")
        for field in ("allocatedSeconds", "elapsedSeconds"):
            value = record.get(field)
            if (type(value) not in (int, float) or not math.isfinite(value)
                    or not 0 <= value <= TRACE_TIMING_LIMIT_SECONDS):
                raise ValueError("Invalid closed onboarding trace timing")
    return payload


class ClosedOnboardingTrace:
    def __init__(self):
        self.payload = empty_trace_payload()
        self.lock = Lock()

    def __call__(self, event, side, attempt, allocated, elapsed):
        values = (allocated, elapsed)
        if (event not in ONBOARDING_TRACE_EVENTS or side not in TRACE_SIDES
                or type(attempt) is not int or not 0 <= attempt <= 2
                or not all(type(value) in (int, float) and math.isfinite(value)
                           and 0 <= value <= TRACE_TIMING_LIMIT_SECONDS for value in values)):
            raise ValueError("Invalid closed onboarding trace event")
        record = {
            "event": event,
            "side": side,
            "attempt": attempt,
            "allocatedSeconds": round(float(allocated), 3),
            "elapsedSeconds": round(float(elapsed), 3),
        }
        with self.lock:
            self.payload["counts"][event] += 1
            if len(self.payload["events"]) < MAX_TRACE_EVENTS:
                self.payload["events"].append(record)
            else:
                self.payload["droppedEvents"] += 1
                self.payload["events"].pop(TRACE_PREFIX_EVENTS)
                self.payload["events"].append(record)


def validated_trace_payload(trace):
    if not isinstance(trace, ClosedOnboardingTrace):
        raise ValueError("Invalid closed onboarding trace container")
    with trace.lock:
        payload = {
            "schemaVersion": trace.payload["schemaVersion"],
            "events": [dict(record) for record in trace.payload["events"]],
            "counts": dict(trace.payload["counts"]),
            "droppedEvents": trace.payload["droppedEvents"],
        }
    return validate_trace_payload(payload)
