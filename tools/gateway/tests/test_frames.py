import json
import socket
import struct

from frames import encode_frame, frame_ack, frame_hello, frame_msg, frame_partial, frame_ping, frame_pong, read_frame


def test_encode_frame_is_length_prefixed_json():
    frame = {"type": "ping", "t0": 123}
    raw = encode_frame(frame)
    (length,) = struct.unpack(">I", raw[:4])
    body = raw[4:]
    assert length == len(body)
    assert json.loads(body.decode("utf-8")) == frame


def test_round_trip_over_a_real_socket():
    a, b = socket.socketpair()
    try:
        write_side, read_side = a, b
        write_side.sendall(encode_frame(frame_hello("dev-1", "Test Device")))
        got = read_frame(read_side)
        assert got == {"type": "hello", "deviceId": "dev-1", "name": "Test Device", "protocol": 1}
    finally:
        a.close()
        b.close()


def test_frame_msg_matches_core_messages_kt_shape():
    frame = frame_msg("dev-1", "evacuate now", "en", True, False)
    assert frame["type"] == "msg"
    m = frame["message"]
    for key in ("id", "from", "lang", "text", "priority", "emotion", "ssml", "speechEndAt", "sttDoneAt", "sentAt"):
        assert key in m, f"missing VoiceMessage field: {key}"
    assert m["from"] == "dev-1"
    assert m["lang"] == "EN"  # Lang enum serializes by name (see core/Messages.kt)
    assert m["text"] == "evacuate now"
    assert m["priority"] == "ALERT"
    assert m["ssml"] is False


def test_frame_msg_normal_priority_when_not_alert():
    frame = frame_msg("dev-1", "hello", "hi", False, False)
    assert frame["message"]["priority"] == "NORMAL"
    assert frame["message"]["lang"] == "HI"


def test_frame_ack_shape():
    frame = frame_ack("msg-1", 1000, 1050)
    assert frame == {"type": "ack", "id": "msg-1", "receivedAt": 1000, "playStartedAt": 1050}


def test_frame_partial_shape_matches_frame_partial_in_messages_kt():
    frame = frame_partial("msg-1", "dev-1", "ta", "partial transcript...")
    assert frame == {"type": "partial", "id": "msg-1", "from": "dev-1", "lang": "TA", "text": "partial transcript..."}


def test_frame_ping_pong_roundtrip():
    ping = frame_ping(1000)
    assert ping == {"type": "ping", "t0": 1000}
    pong = frame_pong(1000, 1010)
    assert pong == {"type": "pong", "t0": 1000, "t1": 1010}
