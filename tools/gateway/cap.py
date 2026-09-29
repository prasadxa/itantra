"""
cap.py - minimal OASIS CAP 1.2 (Common Alerting Protocol) ingestion.

SACHET (the Indian govt multi-hazard alert system this gateway would sit under in a real
deployment) publishes alerts as CAP 1.2 XML. We only need enough of the spec to turn one
<alert> into one iTantra Frame.Msg: pick the <info> block for a target language (falling
back to English, then to whatever's first), and build a short spoken-friendly text from
its <headline>/<description>.

Reference: OASIS CAP v1.2 (http://docs.oasis-open.org/emergency/cap/v1.2/CAP-v1.2-os.pdf).
Namespace: urn:oasis:names:tc:emergency:cap:1.2
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Optional
from xml.etree import ElementTree as ET

CAP_NS = "urn:oasis:names:tc:emergency:cap:1.2"


def _ns(tag: str) -> str:
    return f"{{{CAP_NS}}}{tag}"


def _text(el: Optional[ET.Element]) -> Optional[str]:
    if el is None or el.text is None:
        return None
    t = el.text.strip()
    return t or None


@dataclass
class CapInfo:
    language: str
    event: Optional[str]
    headline: Optional[str]
    description: Optional[str]
    instruction: Optional[str]
    severity: Optional[str]
    urgency: Optional[str]
    certainty: Optional[str]
    area_desc: Optional[str]
    effective: Optional[str]
    expires: Optional[str]

    def spoken_text(self) -> str:
        """A short text suitable for Frame.Msg.message.text / TTS: headline, else
        event + description, trimmed to something a walkie-talkie announcement can carry."""
        parts = [p for p in (self.headline, self.description) if p]
        if not parts and self.event:
            parts = [self.event]
        text = ". ".join(parts) if parts else "Alert."
        return text.strip()


@dataclass
class CapAlert:
    identifier: Optional[str]
    sender: Optional[str]
    sent: Optional[str]
    status: Optional[str]
    msg_type: Optional[str]
    scope: Optional[str]
    infos: list[CapInfo]

    def pick_info(self, target_lang: str = "en") -> CapInfo:
        """Picks the <info> block matching target_lang (matched on the primary subtag,
        e.g. "hi" matches "hi-IN"/"hi"), else the English block, else the first block."""
        target = target_lang.split("-")[0].lower()

        def primary(code: Optional[str]) -> str:
            return (code or "en").split("-")[0].lower()

        for info in self.infos:
            if primary(info.language) == target:
                return info
        for info in self.infos:
            if primary(info.language) == "en":
                return info
        if self.infos:
            return self.infos[0]
        raise ValueError("CAP alert has no <info> blocks")


def _reject_unsafe_xml(xml_text: str) -> None:
    """CAP feeds (SACHET etc.) may originate off-device; stdlib ElementTree has no XXE/entity-
    expansion protection, so refuse any DOCTYPE/ENTITY declaration outright rather than pull in
    an extra dependency (defusedxml) for a project that otherwise has none. Trusted, entity-free
    CAP 1.2 documents (like the samples/ here) parse normally."""
    lowered = xml_text.lower()
    if "<!doctype" in lowered or "<!entity" in lowered:
        raise ValueError("refusing to parse CAP XML containing DOCTYPE/ENTITY declarations (XXE/billion-laughs guard)")


def parse_cap_file(path: str | Path, target_lang: str = "en") -> tuple[CapAlert, CapInfo]:
    """Parses a CAP 1.2 XML file and returns (full alert, chosen info block)."""
    xml_text = Path(path).read_text(encoding="utf-8")
    return parse_cap_string(xml_text, target_lang)


def parse_cap_string(xml_text: str, target_lang: str = "en") -> tuple[CapAlert, CapInfo]:
    _reject_unsafe_xml(xml_text)
    root = ET.fromstring(xml_text)
    return _parse_root(root, target_lang)


def _parse_root(root: ET.Element, target_lang: str) -> tuple[CapAlert, CapInfo]:
    if root.tag != _ns("alert"):
        # Be lenient: some feeds omit/typo the namespace. Try tag suffix match.
        if not root.tag.endswith("alert"):
            raise ValueError(f"not a CAP <alert> root: {root.tag}")

    def find(tag: str) -> Optional[ET.Element]:
        return root.find(_ns(tag)) if root.tag.startswith("{") else root.find(tag)

    def findall(tag: str) -> list[ET.Element]:
        return root.findall(_ns(tag)) if root.tag.startswith("{") else root.findall(tag)

    infos: list[CapInfo] = []
    for info_el in findall("info"):
        def itext(tag: str) -> Optional[str]:
            return _text(info_el.find(_ns(tag)) if info_el.tag.startswith("{") or root.tag.startswith("{") else info_el.find(tag))

        area_el = info_el.find(_ns("area")) if root.tag.startswith("{") else info_el.find("area")
        area_desc = None
        if area_el is not None:
            area_desc_el = area_el.find(_ns("areaDesc")) if root.tag.startswith("{") else area_el.find("areaDesc")
            area_desc = _text(area_desc_el)

        infos.append(
            CapInfo(
                language=itext("language") or "en-US",
                event=itext("event"),
                headline=itext("headline"),
                description=itext("description"),
                instruction=itext("instruction"),
                severity=itext("severity"),
                urgency=itext("urgency"),
                certainty=itext("certainty"),
                area_desc=area_desc,
                effective=itext("effective"),
                expires=itext("expires"),
            )
        )

    alert = CapAlert(
        identifier=_text(find("identifier")),
        sender=_text(find("sender")),
        sent=_text(find("sent")),
        status=_text(find("status")),
        msg_type=_text(find("msgType")),
        scope=_text(find("scope")),
        infos=infos,
    )
    chosen = alert.pick_info(target_lang)
    return alert, chosen
