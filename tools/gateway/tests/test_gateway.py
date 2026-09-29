from pathlib import Path

from db import GatewayDB, percentile
from gateway import _extract_location


def test_extract_location_absent_field_is_handled_gracefully():
    # VoiceMessage has no lat/lon field yet (core/Messages.kt) - must not raise, must return Nones.
    assert _extract_location({"id": "1", "text": "hi"}) == (None, None)


def test_extract_location_top_level_fields():
    assert _extract_location({"lat": 12.9, "lon": 77.6}) == (12.9, 77.6)


def test_extract_location_lng_alias():
    assert _extract_location({"lat": 12.9, "lng": 77.6}) == (12.9, 77.6)


def test_extract_location_nested_dict():
    assert _extract_location({"location": {"lat": 12.9, "lng": 77.6}}) == (12.9, 77.6)


def test_extract_location_malformed_is_none_not_raise():
    assert _extract_location({"lat": "not-a-number", "lon": 1}) == (None, None)


def test_db_lang_counts_and_latency(tmp_path: Path):
    db = GatewayDB(tmp_path / "log.db")
    db.log_message(
        msg_id="m1", direction="out", peer_device_id="p1", from_id="gw", lang="EN", text="hi",
        priority="NORMAL", emotion=None, ssml=False, speech_end_at=0, stt_done_at=0, sent_at=1000,
    )
    db.log_message(
        msg_id="m2", direction="in", peer_device_id="p1", from_id="p1", lang="EN", text="reply",
        priority="NORMAL", emotion=None, ssml=False, speech_end_at=0, stt_done_at=0, sent_at=1000,
    )
    db.log_ack(msg_id="m1", peer_device_id="p1", direction="in", received_at=1200, play_started_at=1250, sent_at_gateway=1000)

    counts = {row["lang"]: row["n"] for row in db.lang_counts()}
    assert counts["EN"] == 2

    lat = db.latency_samples()
    assert lat["sent_to_received_ms"] == [200]
    assert lat["received_to_play_ms"] == [50]


def test_percentile_basic():
    assert percentile([], 50) is None
    assert percentile([10], 50) == 10
    assert percentile([10, 20, 30, 40], 50) == 25
