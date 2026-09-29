"""Build the iTantra SIH 2026 idea deck on the official SIH template (template.pptx) with python-pptx.
The template frame (title placeholders, SIH logo, team-name oval, footer bar, slide numbers) is kept; the
idea-detail pointer headings are kept word for word as section labels; content is added as diagrams,
icons, photos, screenshots and one native chart. Slide 7 (instructions) is removed (max 6 slides)."""
import copy, json
from lxml import etree
from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE, MSO_CONNECTOR
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.chart.data import CategoryChartData
from pptx.enum.chart import XL_CHART_TYPE, XL_LABEL_POSITION
from pptx.oxml.ns import qn

IN = Inches
INK, MUTED, LINE = "1B2433", "5B6472", "DDE1E7"
CARD = "F6F7F9"
SA, SA_SOFT = "EA580C", "FFF1E8"
TE, TE_SOFT = "0F766E", "E6F4F2"
GREEN, GREEN_SOFT = "15803D", "E8F5EC"
AMBER, AMBER_SOFT = "B45309", "FDF3E3"
SLATE, SLATE_SOFT = "64748B", "EEF1F5"
FONT = "Arial"
TEAM = "Team Turtle"

def rgb(h): return RGBColor.from_string(h)

# ---------- helpers ----------
def remove(shape): el = shape._element; el.getparent().remove(el)

def box(sl, x, y, w, h, fill=None, line=None, radius=None, shape=None, lw=0.75):
    shp = sl.shapes.add_shape(shape or (MSO_SHAPE.ROUNDED_RECTANGLE if radius is not None else MSO_SHAPE.RECTANGLE), IN(x), IN(y), IN(w), IN(h))
    if radius is not None and shp.adjustments and len(shp.adjustments) > 0: shp.adjustments[0] = radius
    if fill: shp.fill.solid(); shp.fill.fore_color.rgb = rgb(fill)
    else: shp.fill.background()
    if line: shp.line.color.rgb = rgb(line); shp.line.width = Pt(lw)
    else: shp.line.fill.background()
    shp.shadow.inherit = False
    if shp.has_text_frame: shp.text_frame.text = ""
    return shp

def text(sl, x, y, w, h, paras, size=12, color=INK, bold=False, align=PP_ALIGN.LEFT, anchor=MSO_ANCHOR.TOP, font=FONT, spacing_after=0, line_spacing=None):
    """paras: str | list of str | list of list of runs, run = str | (str, {size,bold,color,italic,link})"""
    tb = sl.shapes.add_textbox(IN(x), IN(y), IN(w), IN(h))
    tf = tb.text_frame; tf.word_wrap = True; tf.auto_size = None
    tf.margin_left = tf.margin_right = tf.margin_top = tf.margin_bottom = 0
    tf.vertical_anchor = anchor
    if isinstance(paras, str): paras = [paras]
    for i, para in enumerate(paras):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.alignment = align; p.space_after = Pt(spacing_after)
        if line_spacing: p.line_spacing = line_spacing
        runs = para if isinstance(para, list) else [para]
        for r in runs:
            t, o = (r, {}) if isinstance(r, str) else r
            run = p.add_run(); run.text = t
            f = run.font; f.name = font; f.size = Pt(o.get("size", size)); f.bold = o.get("bold", bold)
            f.italic = o.get("italic", False); f.color.rgb = rgb(o.get("color", color))
            if o.get("link"): run.hyperlink.address = o["link"]
    return tb

def icon(sl, name, color, x, y, s): return sl.shapes.add_picture(f"icons/{name}-{color}.png", IN(x), IN(y), IN(s), IN(s))

def icon_disc(sl, name, cx, cy, d, fill, icolor="white", ratio=0.56):
    box(sl, cx - d / 2, cy - d / 2, d, d, fill=fill, shape=MSO_SHAPE.OVAL)
    s = d * ratio; icon(sl, name, icolor, cx - s / 2, cy - s / 2, s)

def pic(sl, path, x, y, w=None, h=None, rounded=None, border=None):
    p = sl.shapes.add_picture(path, IN(x), IN(y), IN(w) if w else None, IN(h) if h else None)
    if rounded is not None:
        geom = p._element.spPr.find(qn("a:prstGeom")); geom.set("prst", "roundRect")
        av = geom.find(qn("a:avLst"))
        if av is None: av = etree.SubElement(geom, qn("a:avLst"))
        gd = etree.SubElement(av, qn("a:gd")); gd.set("name", "adj"); gd.set("fmla", f"val {int(rounded * 100000)}")
    if border: p.line.color.rgb = rgb(border); p.line.width = Pt(0.75)
    return p

def arrow(sl, x1, y1, x2, y2, color=MUTED, w=1.5, dash=False):
    c = sl.shapes.add_connector(MSO_CONNECTOR.STRAIGHT, IN(x1), IN(y1), IN(x2), IN(y2))
    c.line.color.rgb = rgb(color); c.line.width = Pt(w)
    ln = c.line._get_or_add_ln()
    if dash:
        pd = etree.SubElement(ln, qn("a:prstDash")); pd.set("val", "dash")
    te = etree.SubElement(ln, qn("a:tailEnd")); te.set("type", "triangle"); te.set("w", "med"); te.set("len", "med")
    return c

def label(sl, x, y, w, t, color=INK, size=13, h=0.3):
    """Template pointer heading, kept verbatim."""
    return text(sl, x, y, w, h, [[(t, {"bold": True, "color": color, "size": size})]])

def chip(sl, x, y, w, t, fg, bg, size=9.5, h=0.26):
    b = box(sl, x, y, w, h, fill=bg, radius=0.5)
    text(sl, x, y, w, h, [[(t, {"bold": True, "color": fg, "size": size})]], align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE)
    return b

def set_team(sl):
    for sh in sl.shapes:
        if sh.has_text_frame and sh.text_frame.text.strip() == "Your Team Name":
            p = sh.text_frame.paragraphs; r0 = p[0].runs[0]
            for extra in p[1:]: extra._p.getparent().remove(extra._p)
            for extra in p[0].runs[1:]: extra._r.getparent().remove(extra._r)
            r0.text = TEAM; r0.font.size = Pt(12); r0.font.bold = True

def set_title(sl, t, size=None):
    tp = sl.shapes.title; r = tp.text_frame.paragraphs[0].runs[0]; r.text = t
    for extra in tp.text_frame.paragraphs[0].runs[1:]: extra._r.getparent().remove(extra._r)
    if size: r.font.size = Pt(size)

def pointer_box(sl):
    for sh in sl.shapes:
        if sh.shape_type == 17 and sh.name == "TextBox 8": return sh

def footnote(sl, y, t, h=0.22, x=0.45, w=12.4):
    return text(sl, x, y, w, h, [[(t, {"size": 9, "color": MUTED, "italic": True})]])

# ---------- open template, drop instructions slide ----------
prs = Presentation("SIH2026-IDEA-Presentation-Format.pptx")
sld = prs.slides._sldIdLst; last = sld[-1]
prs.part.drop_rel(last.get(qn("r:id"))); sld.remove(last)
S = list(prs.slides)
credits = json.load(open("photos/credits.json"))

SITE = "https://itantra-106.pages.dev/"
DEMO = "https://itantra-106.pages.dev/demo/"
CODE = "https://github.com/prasadxa/itantra"
APK = "https://pub-d139da76dbb3434bbcb332e210a95fcf.r2.dev/apk/iTantra-0.1.0-debug.apk"
MODELS = "https://pub-d139da76dbb3434bbcb332e210a95fcf.r2.dev/models/v1/manifest.json"

def linkify(shape, url):
    shape.click_action.hyperlink.address = url
    return shape

def pill(sl, x, y, w, ic, t, url, fg=TE, bg=TE_SOFT, h=0.36, size=10):
    linkify(box(sl, x, y, w, h, fill=bg, radius=0.5), url)
    icon(sl, ic, "te" if fg == TE else ("sa" if fg == SA else "ink"), x + 0.12, y + (h - 0.24) / 2, 0.24)
    linkify(text(sl, x + 0.42, y, w - 0.5, h, [[(t, {"bold": True, "size": size, "color": fg})]], anchor=MSO_ANCHOR.MIDDLE), url)

def fit_pic(sl, path, x, y, w, h, url=None, border=None):
    """Picture filling (x, y, w, h), centre-cropped to that box."""
    from PIL import Image
    im = Image.open(path); ar = im.width / im.height; target = w / h
    p = sl.shapes.add_picture(path, IN(x), IN(y), IN(w), IN(h))
    if ar > target:
        c = (1 - target / ar) / 2; p.crop_left = c; p.crop_right = c
    else:
        c = (1 - ar / target) / 2; p.crop_top = c * 0.6; p.crop_bottom = c * 1.4
    if border: p.line.color.rgb = rgb(border); p.line.width = Pt(0.75)
    if url: linkify(p, url)
    return p

def phone_pic(sl, path, x, y, h, url=None):
    p = pic(sl, path, x, y, h=h, rounded=0.08, border=LINE)
    if url: linkify(p, url)
    return p

# =====================================================================================
# SLIDE 1 — title page: template fields + brand lock-up + clickable links
s = S[0]
fields = {"Problem Statement ID": "SIH26173",
          "Problem Statement Title": "iTantra – Indian Multilingual TTS & STT Aided Neural Transceiver Radio Access for low bitrate links",
          "Theme": "Smart Automation", "PS Category": "Software", "Team ID": "188881",
          "Team Name (Registered on portal)": TEAM}
for sh in s.shapes:
    if sh.has_text_frame and "Problem Statement ID" in sh.text_frame.text:
        for p in sh.text_frame.paragraphs:
            t = p.text
            for k, v in fields.items():
                if t.strip().startswith(k):
                    if k == "PS Category":
                        for r in p.runs: r.text = r.text.replace("Software/Hardware", "").rstrip()
                    for lr in p.runs: lr.font.size = Pt(19)
                    r = p.add_run(); r.text = " " + v
                    r.font.name = FONT; r.font.bold = False; r.font.color.rgb = rgb(SA if k != "Problem Statement Title" else INK)
                    r.font.size = Pt(13 if k == "Problem Statement Title" else 17)
                    p.alignment = PP_ALIGN.LEFT; p.space_before = Pt(4); p.space_after = Pt(4); p.line_spacing = 1.0
                    break
px = 0.62
for ic, t, url, w in [("globe", "Website", SITE, 1.35), ("film", "Demo film", DEMO, 1.5), ("github", "Code", CODE, 1.05), ("download", "APK", APK, 1.0)]:
    pill(s, px, 5.72, w, ic, t, url, size=10.5); px += w + 0.12
linkify(pic(s, "img/logo.png", 7.62, 6.66, h=0.56, rounded=0.2), SITE)
text(s, 8.3, 6.64, 3.4, 0.62, [[("iTantra", {"bold": True, "size": 18, "color": INK})],
                             [("Speak. Send text. Hear it back — offline.", {"size": 11, "color": MUTED})]])

# =====================================================================================
# SLIDE 2 — IDEA: problem + idea, pipeline, three pointer columns, two real phone screens
s = S[1]; set_team(s); set_title(s, "iTantra: Speak. Send Text. Hear It Back — Offline", 26)
remove(pointer_box(s))
LW = 8.5
label(s, 0.45, 1.3, LW, "Proposed Solution (Describe your Idea/Solution/Prototype)", size=13)
text(s, 0.45, 1.6, LW, 0.42, [[("Problem: ", {"bold": True, "color": SA}),
                               ("when towers fail after a flood or cyclone, only thin links remain — Bluetooth, a crowded hotspot or LoRa radio at ~250 bps. "
                                "Voice needs 256 kbps raw, ~12 kbps with a good codec.", {})]], size=10.5)
text(s, 0.45, 2.03, LW, 0.42, [[("Our idea: ", {"bold": True, "color": TE}),
                                ("send the meaning, not the sound. Speech becomes text on the phone, only the text travels, and it is spoken again on arrival. ", {}),
                                ("No tower, no internet, no cloud.", {"bold": True, "color": SA})]], size=10.5)
by, bh = 2.52, 1.08
box(s, 0.45, by, LW, bh, fill=CARD, radius=0.1)
steps = [("mic", SA, "Speak", "hold to talk; a pause ends it"), ("wave", SA, "Speech → text", "on the phone, 10 languages"),
         ("bytes", SA, "Tiny frame", "≈62 bytes per sentence*"), ("wifi", INK, "Any link", "Wi-Fi · Direct · BLE · LoRa"),
         ("speaker", TE, "Text → speech", "ALERT at alarm volume")]
sw = LW / 5
for i, (ic, col, t1, t2) in enumerate(steps):
    cx = 0.45 + i * sw + sw / 2
    icon_disc(s, ic, cx, by + 0.34, 0.46, col)
    text(s, cx - sw / 2, by + 0.6, sw, 0.22, [[(t1, {"bold": True, "size": 10.5, "color": col})]], align=PP_ALIGN.CENTER)
    text(s, cx - sw / 2 + 0.05, by + 0.82, sw - 0.1, 0.2, [[(t2, {"size": 8.5, "color": MUTED})]], align=PP_ALIGN.CENTER)
    if i < 4: arrow(s, cx + 0.33, by + 0.34, cx + sw - 0.33, by + 0.34, color="9AA3AF")

cy, chh = 3.72, 2.93
cw = (LW - 0.2) / 3
for i in range(3): box(s, 0.45 + i * (cw + 0.1), cy, cw, chh, fill="FFFFFF", line=LINE, radius=0.05)
def rows(x, items, color, y0=cy + 0.52, step=0.6):
    for i, (ic, t1, t2) in enumerate(items):
        yy = y0 + i * step
        icon(s, ic, color, x + 0.12, yy + 0.02, 0.26)
        text(s, x + 0.47, yy, cw - 0.55, 0.58, [[(t1, {"bold": True, "size": 10})], [(t2, {"size": 8.5, "color": MUTED})]])
label(s, 0.57, cy + 0.1, cw - 0.2, "Detailed explanation of the proposed solution", size=10.5, h=0.4)
rows(0.45, [("langs", "10 Indian languages", "hi · bn · mr · te · ta · gu · kn · ml · or · en, speech in and out"),
            ("siren", "ALERT that cannot be missed", "Alarm stream, full volume, vibration (16/16 in test)"),
            ("pin", "One-tap SOS with location", "6 templates + GPS/NavIC, read aloud on arrival"),
            ("mic", "Talk, call or captions", "Push-to-talk, hands-free call, live captions")], "sa")
x2 = 0.45 + cw + 0.1
label(s, x2 + 0.12, cy + 0.1, cw - 0.2, "How it addresses the problem", size=10.5)
cd = CategoryChartData(); cd.categories = ["Raw audio", "Opus voice", "iTantra"]
cd.add_series("bps", (256000, 12000, 165))
gf = s.shapes.add_chart(XL_CHART_TYPE.BAR_CLUSTERED, IN(x2 + 0.05), IN(cy + 0.36), IN(cw - 0.12), IN(1.2), cd)
ch_ = gf.chart; ch_.has_legend = False; ch_.has_title = False
pl = ch_.plots[0]; pl.gap_width = 45; pl.vary_by_categories = False
ser = pl.series[0]
for idx, col in enumerate(("9AA3AF", "9AA3AF", SA)):
    pt = ser.points[idx]; pt.format.fill.solid(); pt.format.fill.fore_color.rgb = rgb(col)
pl.has_data_labels = True; dl = pl.data_labels
dl.number_format = '[>=1000]0,"k";0'; dl.number_format_is_linked = False
dl.position = XL_LABEL_POSITION.OUTSIDE_END; dl.font.size = Pt(9); dl.font.bold = True; dl.font.color.rgb = rgb(INK)
va = ch_.value_axis; va.visible = False; va.has_major_gridlines = False
va.minimum_scale = 10; va.maximum_scale = 100000000
scaling = va._element.find(qn("c:scaling")); lb = etree.SubElement(scaling, qn("c:logBase")); lb.set("val", "10")
scaling.remove(lb); scaling.insert(0, lb)
ca = ch_.category_axis; ca.tick_labels.font.size = Pt(9); ca.tick_labels.font.color.rgb = rgb(INK); ca.format.line.fill.background()
ca.reverse_order = True
text(s, x2 + 0.12, cy + 1.56, cw - 0.2, 0.2, [[("bits/s for one spoken sentence (log scale)", {"size": 8, "color": MUTED, "italic": True})]])
for i, (b, t) in enumerate([("≈1,500× less data", " than raw audio; fits LoRa's ~250 bps"),
                            ("62 B", " per sentence vs 524 B as plain JSON"),
                            ("Output is still speech", " — people who cannot read are included")]):
    text(s, x2 + 0.12, cy + 1.82 + i * 0.36, cw - 0.2, 0.36, [[(b, {"bold": True, "size": 9.5, "color": SA if i < 2 else TE}), (t, {"size": 9.5})]])
x3 = 0.45 + 2 * (cw + 0.1)
label(s, x3 + 0.12, cy + 0.1, cw - 0.2, "Innovation and uniqueness of the solution", size=10.5, h=0.4)
rows(x3, [("layers", "Speech itself is the codec", "Text on the wire, voice rebuilt in the listener's language"),
          ("link", "One frame, any link", "Wi-Fi → Wi-Fi Direct → BLE, no user action"),
          ("shield", "Trusted alerts", "Ed25519-signed ALERTs; encrypted after QR pairing"),
          ("gauge", "Adapts to the phone", "LITE profile switches on below 6 GB RAM")], "te")
footnote(s, 6.7, "*Median of 310 FLEURS sentences (unit test), before encryption and link headers. Bars assume a ~3 s sentence.", h=0.2, w=LW)

ph = 3.95; pw = ph * 540 / 1158
phone_pic(s, "img/03_sos_sheet.jpg", 12.88 - 2 * pw - 0.12, 1.32, ph, url=DEMO)
phone_pic(s, "img/01_alert_card.jpg", 12.88 - pw, 1.32, ph, url=DEMO)
text(s, 12.88 - 2 * pw - 0.12, 5.3, pw, 0.22, [[("Sender · SOS", {"bold": True, "size": 9.5, "color": SA})]], align=PP_ALIGN.CENTER)
text(s, 12.88 - pw, 5.3, pw, 0.22, [[("Receiver · ALERT", {"bold": True, "size": 9.5, "color": TE})]], align=PP_ALIGN.CENTER)
pill(s, 9.1, 5.62, 3.78, "film", "Watch the demo film  ·  1 min 55 s", DEMO, fg=SA, bg=SA_SOFT, size=9.5)
text(s, 9.1, 6.08, 3.78, 0.6, [[("Real screens from the running prototype (v0.1.0) on a vivo I2202, Snapdragon 870. ", {"size": 8.5, "color": MUTED}),
                               ("Try it: itantra-106.pages.dev", {"size": 8.5, "color": TE, "bold": True, "link": SITE})]])

# =====================================================================================
# SLIDE 3 — TECHNICAL APPROACH: detailed stack, flow, status, real screens
s = S[2]; set_team(s); remove(pointer_box(s))
label(s, 0.45, 1.3, 3.9, "Technologies to be used (e.g. programming languages, frameworks, hardware)", size=11, h=0.45)
stack = [("android", "Android app", "Kotlin 2.4 · Jetpack Compose · Android 8.0+, arm64"),
         ("ear", "Voice detection", "Silero VAD (0.6 MB): a pause ends the sentence"),
         ("wave", "Speech-to-text", "SraVaani-1.0 (IISc), int8 ONNX · sherpa-onnx"),
         ("speaker", "Text-to-speech", "VITS Rasa (6 langs) · Indic-Mio (4 langs)"),
         ("wifi", "Links", "Wi-Fi mDNS/TCP → Wi-Fi Direct → BLE GATT"),
         ("lock", "Security", "X25519 · ChaCha20-Poly1305 · Ed25519 · QR pairing"),
         ("antenna", "Radio hardware", "ESP32-S3 + SX1262 LoRa, 865–867 MHz"),
         ("server", "Control room", "Python gateway · SQLite · CAP 1.2 alerts")]
for i, (ic, t1, t2) in enumerate(stack):
    yy = 1.84 + i * 0.6
    box(s, 0.45, yy, 3.9, 0.53, fill=CARD, radius=0.2)
    icon_disc(s, ic, 0.75, yy + 0.265, 0.38, TE if i in (2, 3) else INK)
    text(s, 1.06, yy + 0.05, 3.22, 0.22, [[(t1, {"bold": True, "size": 10})]])
    text(s, 1.06, yy + 0.27, 3.22, 0.22, [[(t2, {"size": 8.5, "color": MUTED})]])

label(s, 4.6, 1.3, 8.3, "Methodology and process for implementation (Flow Charts/Images/ working prototype)", size=11)
lanes = [("SENDER PHONE", SA, SA_SOFT, [("mic", "Mic 16 kHz"), ("ear", "VAD: cut at pause"), ("wave", "STT on device"), ("bytes", "Pack · sign · encrypt")]),
         ("ANY LINK — same frame", INK, SLATE_SOFT, [("wifi", "Wi-Fi LAN (first)"), ("antenna", "Wi-Fi Direct (+4 s)"), ("bt", "Bluetooth LE (+8 s)"), ("server", "Control room / LoRa")]),
         ("RECEIVER PHONE", TE, TE_SOFT, [("check", "Verify · decode · ACK"), ("text", "Numbers → words"), ("speaker", "TTS in own language"), ("siren", "Voice note / ALERT")])]
ly, lh, lw = 1.66, 2.08, 2.62
for i, (title, col, soft, nodes) in enumerate(lanes):
    x = 4.6 + i * (lw + 0.21)
    box(s, x, ly, lw, lh, fill=soft, radius=0.07)
    text(s, x, ly + 0.06, lw, 0.22, [[(title, {"bold": True, "size": 9, "color": col})]], align=PP_ALIGN.CENTER)
    for j, (ic, t) in enumerate(nodes):
        ny = ly + 0.33 + j * 0.43
        box(s, x + 0.15, ny, lw - 0.3, 0.34, fill="FFFFFF", line=LINE, radius=0.25)
        icon(s, ic, "sa" if col == SA else ("te" if col == TE else "ink"), x + 0.26, ny + 0.06, 0.22)
        text(s, x + 0.56, ny, lw - 0.8, 0.34, [[(t, {"size": 9.5})]], anchor=MSO_ANCHOR.MIDDLE)
        if j < 3: arrow(s, x + lw / 2, ny + 0.34, x + lw / 2, ny + 0.43, color="9AA3AF", w=1.25)
    if i < 2: arrow(s, x + lw + 0.02, ly + lh / 2, x + lw + 0.19, ly + lh / 2, color=INK, w=2)

for i, (t, fg, bg, d) in enumerate([("BUILT", GREEN, GREEN_SOFT, "Push-to-talk, call, 10-language STT/TTS, ALERT, SOS, LITE, control room"),
                                    ("PARTIAL · TEST PENDING", AMBER, AMBER_SOFT, "Encryption (binary mode); LoRa firmware compiles; Direct/BLE two-phone test"),
                                    ("PLANNED", SLATE, SLATE_SOFT, "On-device translation (IndicTrans2), smaller voices, phone mesh relay")]):
    x = 4.6 + i * 2.8
    box(s, x, 3.86, 2.7, 0.88, fill="FFFFFF", line=LINE, radius=0.08)
    chip(s, x + 0.1, 3.94, 1.9 if i == 1 else 1.1, t, fg, bg, size=8, h=0.24)
    text(s, x + 0.12, 4.22, 2.5, 0.5, [[(d, {"size": 8.5})]])

text(s, 4.6, 4.84, 8.3, 0.22, [[("Working prototype — real screens", {"bold": True, "size": 10})]])
sh_h, sy = 1.5, 5.1
x = 4.6
for pth, cap in [("img/dashboard.jpg", "PC control room"), ("img/pairing.jpg", "QR pairing"),
                 ("img/language_picker.jpg", "Language"), ("img/08_hindi_ui.jpg", "LITE profile")]:
    if "dashboard" in pth:
        w = sh_h * 1376 / 632
        linkify(pic(s, pth, x, sy, w=w, h=sh_h, rounded=0.04, border=LINE), SITE)
    else:
        p = phone_pic(s, pth, x, sy, sh_h, url=SITE); w = p.width / 914400
    text(s, x - 0.1, sy + sh_h + 0.03, w + 0.2, 0.18, [[(cap, {"size": 8, "color": MUTED})]], align=PP_ALIGN.CENTER)
    x += w + 0.12
tx = x + 0.05
facts = [("Prototype v0.1.0", True), ("APK 60 MB · Android 8.0+", False), ("Models 1.2 GB, installed once", False),
         ("Tested: Snapdragon 870 and 8 Gen 3", False)]
text(s, tx, sy - 0.02, 12.88 - tx, 1.3, [[(t, {"size": 9, "bold": b, "color": INK if b else MUTED})] for t, b in facts], spacing_after=3)
pill(s, tx, sy + 1.2, 12.88 - tx, "download", "Reviewer install guide", SITE + "#try", size=8.5, h=0.3)

# =====================================================================================
# SLIDE 4 — FEASIBILITY: measured tiles, risks -> strategies, viability, real metrics screen
s = S[3]; set_team(s); remove(pointer_box(s))
label(s, 0.45, 1.3, 6, "Analysis of the feasibility of the idea", size=14)
text(s, 6.0, 1.34, 6.88, 0.25, [[("Measured: 4 GB-class memory test, in-app logging", {"size": 10.5, "color": MUTED, "italic": True})]], align=PP_ALIGN.RIGHT)
tiles = [("zap", "14×", "faster than speech", "STT real-time factor 0.07, 10/10 langs"), ("clock", "0.6 s", "speech end → text", "median; range 0.42–0.70 s"),
         ("memory", "765 MB", "app RAM, idle", "1.8 GB peak while speaking"), ("cpu", "0.3%", "idle CPU", "of one core, push-to-talk"),
         ("shield", "Alive", "app not killed", "Android closed 115 other processes")]
tw, tg = 2.35, 0.17
for i, (ic, big, l1, l2) in enumerate(tiles):
    x = 0.45 + i * (tw + tg)
    box(s, x, 1.7, tw, 1.36, fill=CARD, radius=0.08)
    icon(s, ic, "te", x + 0.16, 1.8, 0.28)
    text(s, x + 0.16, 2.08, tw - 0.26, 0.46, [[(big, {"bold": True, "size": 24, "color": TE})]])
    text(s, x + 0.16, 2.54, tw - 0.26, 0.22, [[(l1, {"bold": True, "size": 10})]])
    text(s, x + 0.16, 2.76, tw - 0.26, 0.22, [[(l2, {"size": 8.5, "color": MUTED})]])
footnote(s, 3.12, "Method: 12 GB vivo I2202 (Snapdragon 870) with RAM reserved so only ~1.7 GB stayed free, LITE profile. The chip was not slowed, so a budget phone will be slower. "
                  "Also measured: 41 ms message → ACK over Wi-Fi; 0 audio underruns in 5 languages; word error rate 5.9% (preliminary, 10 clips).", h=0.34)

label(s, 0.45, 3.58, 3.3, "Potential challenges and risks", size=12)
label(s, 3.95, 3.58, 3.6, "Strategies for overcoming these challenges", size=12)
risks = [("speaker", "Voice starts 3–6 s after arrival in LITE", "Keep the listener's language loaded; small fast voices for hi/gu/or/en"),
         ("database", "1.14 GB of speech models", "Per-language download on demand; installer checks SHA-256"),
         ("bt", "Wi-Fi Direct / BLE: two-phone test pending", "Two-phone field tests; Wi-Fi stays primary; LoRa bridge for range"),
         ("ear", "Noise, accents, code-mixing", "Alert-keyword boosting; 30-clip-per-language test; typed-text fallback")]
for i, (ic, r, st) in enumerate(risks):
    yy = 3.92 + i * 0.66
    box(s, 0.45, yy, 7.1, 0.6, fill=CARD if i % 2 == 0 else "FFFFFF", line=None if i % 2 == 0 else LINE, radius=0.15)
    icon(s, ic, "sa", 0.58, yy + 0.16, 0.28)
    text(s, 0.96, yy, 2.6, 0.6, [[(r, {"bold": True, "size": 9.5})]], anchor=MSO_ANCHOR.MIDDLE)
    arrow(s, 3.62, yy + 0.3, 3.88, yy + 0.3, color=TE, w=1.5)
    text(s, 3.95, yy, 3.5, 0.6, [[(st, {"size": 9.5})]], anchor=MSO_ANCHOR.MIDDLE)
text(s, 7.8, 3.58, 3.1, 0.3, [[("Viability", {"bold": True, "size": 12})]])
for i, (ic, t1, t2, url) in enumerate([("rupee", "₹0 per message", "No airtime, SIM, server or cloud fees", None),
                                        ("phone", "Phones people already own", "Android 8.0+; LITE mode for less RAM", None),
                                        ("antenna", "Low-cost range extender", "ESP32-S3 + LoRa node ≈ ₹1,900–3,200", None),
                                        ("package", "Open licences", "MIT · Apache-2.0 · CC-BY-4.0 models", MODELS),
                                        ("server", "Fits India's alert flow", "Control room reads CAP 1.2 alert files", None)]):
    yy = 3.93 + i * 0.53
    icon_disc(s, ic, 7.97, yy + 0.2, 0.36, TE)
    tb = text(s, 8.25, yy, 2.75, 0.5, [[(t1, {"bold": True, "size": 9.5})], [(t2, {"size": 8.5, "color": MUTED})]])
    if url: linkify(tb, url)
phone_pic(s, "img/07_metrics.jpg", 11.28, 3.58, 2.98)
text(s, 11.05, 6.6, 1.85, 0.2, [[("In-app metrics (real screen)", {"size": 8, "color": MUTED})]], align=PP_ALIGN.CENTER)

# =====================================================================================
# SLIDE 5 — IMPACT: real photos with who/why, stats, benefits, national alert fit
s = S[4]; set_team(s); remove(pointer_box(s))
label(s, 0.45, 1.3, 8, "Potential impact on the target audience", size=14)
aud = [("ndrf", "lifebuoy", "Disaster response teams", "Coordinate rescue by voice when towers and internet are down"),
       ("fish", "ship", "Coastal fishing communities", "Cyclone warnings spoken in their own language, no data plan"),
       ("rural2", "ear", "Low-literacy users", "An alert they can hear instead of read, in 10 languages"),
       ("rail", "train", "Remote field staff", "A voice link for teams working where coverage is poor")]
cw, cg = 3.0, 0.14
for i, (key, ic, t1, t2) in enumerate(aud):
    x = 0.45 + i * (cw + cg)
    fit_pic(s, f"photos/{key}.jpg", x, 1.66, cw, 1.6, url=credits[key]["page"])
    icon(s, ic, "sa", x, 3.35, 0.26)
    text(s, x + 0.34, 3.32, cw - 0.34, 0.24, [[(t1, {"bold": True, "size": 10.5})]])
    text(s, x + 0.34, 3.56, cw - 0.4, 0.4, [[(t2, {"size": 9, "color": MUTED})]])
for i, ((big, l, col), bw) in enumerate(zip([("≈1,500×", "less data than raw audio*", SA), ("1.03 s", "ALERT arrival → spoken aloud (Wi-Fi)", TE),
                                             ("10", "Indian languages, speech in and out", INK)], (1.75, 1.2, 0.55))):
    x = 0.45 + i * 4.19
    text(s, x, 4.02, bw, 0.52, [[(big, {"bold": True, "size": 26, "color": col})]], anchor=MSO_ANCHOR.MIDDLE)
    text(s, x + bw + 0.08, 4.02, 3.95 - bw, 0.52, [[(l, {"size": 10.5})]], anchor=MSO_ANCHOR.MIDDLE)
label(s, 0.45, 4.62, 12, "Benefits of the solution (social, economic, environmental, etc.)", size=12.5)
for i, (ic, t1, t2, col, soft) in enumerate([
        ("heart", "Social", "Alerts spoken aloud in Indian languages, so people who cannot read are included. SOS carries location to rescuers.", SA, SA_SOFT),
        ("rupee", "Economic", "No airtime, cloud or licence fees. Runs on phones people already own; a LoRa node costs ≈ ₹1,900–3,200.", TE, TE_SOFT),
        ("leaf", "Environmental", "Fewer bytes per message means less radio time on air. Battery gain not yet measured.", GREEN, GREEN_SOFT)]):
    x = 0.45 + i * 4.19
    box(s, x, 4.96, 4.05, 1.0, fill=soft, radius=0.1)
    icon_disc(s, ic, x + 0.4, 5.46, 0.48, col)
    text(s, x + 0.76, 5.02, 3.2, 0.9, [[(t1, {"bold": True, "size": 11, "color": col})], [(t2, {"size": 9})]], anchor=MSO_ANCHOR.MIDDLE)
text(s, 0.45, 6.04, 12.4, 0.26, [[("Fits India's alert system: ", {"bold": True, "size": 10, "color": TE}),
                                  ("the control room reads CAP 1.2 alert files — the format behind ", {"size": 10}),
                                  ("NDMA SACHET", {"size": 10, "bold": True, "color": TE, "link": "https://sachet.ndma.gov.in/"}),
                                  (" — and re-broadcasts them as spoken alerts in each listener's language.", {"size": 10})]])
cr = credits
text(s, 0.45, 6.38, 12.4, 0.4, [[("*62 B per ~3 s sentence (unit-test median) vs 256 kbps raw audio. No field pilot yet.", {"size": 8.5, "color": MUTED, "italic": True})],
                                [("Photos, Wikimedia Commons: ", {"size": 8.5, "color": MUTED, "italic": True}),
                                 ("NDRF – MHA, GODL-India", {"size": 8.5, "color": TE, "italic": True, "link": cr["ndrf"]["page"]}), (" · ", {"size": 8.5, "color": MUTED}),
                                 (f"fishermen – {cr['fish']['artist']}, CC0", {"size": 8.5, "color": TE, "italic": True, "link": cr["fish"]["page"]}), (" · ", {"size": 8.5, "color": MUTED}),
                                 (f"woman on phone – {cr['rural2']['artist']}, CC BY-SA 3.0", {"size": 8.5, "color": TE, "italic": True, "link": cr["rural2"]["page"]}), (" · ", {"size": 8.5, "color": MUTED}),
                                 (f"rail workers – {cr['rail']['artist']}, CC BY-SA 2.0", {"size": 8.5, "color": TE, "italic": True, "link": cr["rail"]["page"]})]])

# =====================================================================================
# SLIDE 6 — RESEARCH AND REFERENCES: every link clickable + 4 QR codes
s = S[5]; set_team(s); remove(pointer_box(s))
label(s, 0.45, 1.3, 9, "Details / Links of the reference and research work", size=14)
H = "https://"
groups = [("brain", "Open models", SA, [
              ("SraVaani-1.0 STT · ARTPARK, IISc", "huggingface.co/ARTPARK-IISc/SraVaani-1.0"),
              ("Indic-Mio TTS · SPRING Lab, IIT-M", "huggingface.co/SPRINGLab/Indic-Mio"),
              ("VITS Rasa 13 TTS · AI4Bharat", "huggingface.co/ai4bharat/vits_rasa_13"),
              ("Silero VAD", "github.com/snakers4/silero-vad"),
              ("IndicTrans2 (planned translation) · AI4Bharat", "github.com/AI4Bharat/IndicTrans2")]),
          ("code", "Runtimes and libraries", TE, [
              ("sherpa-onnx", "github.com/k2-fsa/sherpa-onnx"),
              ("llama.cpp / ggml", "github.com/ggml-org/llama.cpp"),
              ("mio-tts-cpp", "github.com/mmnga/mio-tts-cpp"),
              ("Bouncy Castle (encryption)", "www.bouncycastle.org"),
              ("RadioLib (LoRa)", "github.com/jgromes/RadioLib")]),
          ("book", "Standards and data", INK, [
              ("OASIS CAP 1.2 alerts", "docs.oasis-open.org/emergency/cap/v1.2/CAP-v1.2.html"),
              ("NDMA SACHET", "sachet.ndma.gov.in"),
              ("Google FLEURS (STT test set)", "huggingface.co/datasets/google/fleurs"),
              ("Codec2 (bitrate baseline)", "github.com/drowe67/codec2"),
              ("Meshtastic (LoRa mesh)", "meshtastic.org")]),
          ("android", "Android guides", INK, [
              ("Wi-Fi Direct", "developer.android.com/develop/connectivity/wifi/wifi-direct"),
              ("Bluetooth and BLE", "developer.android.com/develop/connectivity/bluetooth"),
              ("Audio focus (ALERT)", "developer.android.com/media/optimize/audio-focus")]),
          ("github", "Our work", GREEN, [
              ("Source code", "github.com/prasadxa/itantra"),
              ("4 GB-class memory test", "github.com/prasadxa/itantra/tree/main/tools/lowram"),
              ("Model pack (1.2 GB)", "r2.dev/models/v1/manifest.json"),
              ("Website and results", "itantra-106.pages.dev"),
              ("Demo film, 1 min 55 s", "itantra-106.pages.dev/demo"),
              ("Reviewer install guide", "itantra-106.pages.dev/#try")])]
full = {"r2.dev/models/v1/manifest.json": MODELS}
pos = [(0.45, 1.72, 3.2, 2.9), (3.75, 1.72, 3.2, 2.9), (7.05, 1.72, 3.2, 2.9), (0.45, 4.72, 3.9, 2.08), (4.45, 4.72, 5.8, 2.08)]
for (ic, title, col, refs), (x, y, w, h) in zip(groups, pos):
    box(s, x, y, w, h, fill=CARD, radius=0.05)
    icon_disc(s, ic, x + 0.3, y + 0.3, 0.4, col)
    text(s, x + 0.6, y + 0.17, w - 0.7, 0.28, [[(title, {"bold": True, "size": 11.5, "color": col})]])
    for j, (t, url) in enumerate(refs):
        two = len(refs) > 5                      # "Our work": two columns of three
        cx_ = x + 0.2 + (j // 3) * (w / 2 - 0.05) if two else x + 0.2
        yy = y + 0.6 + (j % 3 if two else j) * (0.45 if h > 2.5 else 0.47)
        href = full.get(url, H + url)
        text(s, cx_, yy, (w / 2 - 0.25) if two else w - 0.3, 0.42, [[(t, {"size": 9.5, "bold": True, "link": href, "color": INK})],
                                                                   [(url, {"size": 7.5, "color": TE, "link": href})]])
qrs = [("img/qr-site.png", "Website", "overview · results", SITE), ("img/qr-demo.png", "Demo film", "1 min 55 s", DEMO),
       ("img/qr-github.png", "Code", "GitHub", CODE), ("img/qr-apk.png", "APK", "Android 8+", APK)]
for i, (q, t1, t2, url) in enumerate(qrs):
    y = 1.72 + i * 1.28
    linkify(box(s, 10.4, y, 2.48, 1.18, fill="FFFFFF", line=LINE, radius=0.08), url)
    linkify(pic(s, q, 10.5, y + 0.09, w=1.0, h=1.0), url)
    text(s, 11.6, y, 1.25, 1.18, [[(t1, {"bold": True, "size": 11, "link": url, "color": INK})], [(t2, {"size": 9, "color": MUTED})]], anchor=MSO_ANCHOR.MIDDLE)

prs.save("iTantra_SIH2026_v5.pptx")
print("saved")
