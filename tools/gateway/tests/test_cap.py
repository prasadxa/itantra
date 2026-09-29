from pathlib import Path

import pytest

from cap import parse_cap_file, parse_cap_string

SAMPLES = Path(__file__).resolve().parents[1] / "samples"


def test_samples_exist():
    assert (SAMPLES / "flood_warning.cap.xml").is_file()
    assert (SAMPLES / "cyclone_warning.cap.xml").is_file()


def test_flood_target_language_present():
    alert, info = parse_cap_file(SAMPLES / "flood_warning.cap.xml", target_lang="hi")
    assert info.language.startswith("hi")
    assert "कोशी" in (info.headline or "")
    assert info.event == "बाढ़ की चेतावनी"
    assert alert.identifier == "IN-SACHET-DEMO-FLOOD-0001"


def test_flood_falls_back_to_english_when_target_absent():
    # French isn't in the sample; must fall back to the English block, not raise.
    alert, info = parse_cap_file(SAMPLES / "flood_warning.cap.xml", target_lang="fr")
    assert info.language.startswith("en")
    assert "Flood" in (info.event or "")


def test_cyclone_english_default():
    alert, info = parse_cap_file(SAMPLES / "cyclone_warning.cap.xml", target_lang="en")
    assert info.event == "Cyclone Warning"
    assert info.severity == "Extreme"
    assert "landfall" in (info.description or "")


def test_cyclone_tamil():
    alert, info = parse_cap_file(SAMPLES / "cyclone_warning.cap.xml", target_lang="ta")
    assert info.language.startswith("ta")
    assert info.event == "புயல் எச்சரிக்கை"


def test_spoken_text_prefers_headline_and_description():
    _alert, info = parse_cap_file(SAMPLES / "cyclone_warning.cap.xml", target_lang="en")
    text = info.spoken_text()
    assert info.headline in text
    assert info.description in text


def test_language_matches_regional_subtag():
    # "hi-IN" in the file must match a target of plain "hi".
    _alert, info = parse_cap_file(SAMPLES / "flood_warning.cap.xml", target_lang="hi")
    assert info.language == "hi-IN"


def test_minimal_alert_single_info_block():
    xml = """<?xml version="1.0"?>
    <alert xmlns="urn:oasis:names:tc:emergency:cap:1.2">
      <identifier>TEST-0001</identifier>
      <sender>test@example.org</sender>
      <sent>2026-01-01T00:00:00+00:00</sent>
      <status>Actual</status>
      <msgType>Alert</msgType>
      <scope>Public</scope>
      <info>
        <language>en-US</language>
        <event>Test Event</event>
        <headline>Test headline</headline>
      </info>
    </alert>"""
    alert, info = parse_cap_string(xml, target_lang="hi")  # no hi block -> fallback to the only (en) block
    assert info.event == "Test Event"
    assert alert.status == "Actual"


def test_rejects_doctype_declarations():
    xml = """<?xml version="1.0"?>
    <!DOCTYPE alert [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
    <alert xmlns="urn:oasis:names:tc:emergency:cap:1.2">
      <info><language>en-US</language><event>&xxe;</event></info>
    </alert>"""
    with pytest.raises(ValueError):
        parse_cap_string(xml, target_lang="en")
