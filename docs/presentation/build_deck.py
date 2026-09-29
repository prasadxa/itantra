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
    st = shp._element.find(qn("p:style"))          # theme style adds a shadow in some renderers: keep shapes flat
    if st is not None: shp._element.remove(st)
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
    st = c._element.find(qn("p:style"))
    if st is not None: c._element.remove(st)
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

def bullets(sl, x, y, w, h, items, size=9.5, lead=INK, gap=4, color=INK):
    """items: (lead, rest) pairs -> one bulleted paragraph each, bold lead-in."""
    tb = text(sl, x, y, w, h, [[(a, {"bold": True, "color": lead}), (b, {})] if a else [(b, {})] for a, b in items],
              size=size, color=color, spacing_after=gap)
    for p in tb.text_frame.paragraphs:
        pPr = p._p.get_or_add_pPr(); pPr.set("marL", str(int(IN(0.16)))); pPr.set("indent", str(-int(IN(0.16))))
        bc = etree.SubElement(pPr, qn("a:buFont")); bc.set("typeface", "Arial")
        bu = etree.SubElement(pPr, qn("a:buChar")); bu.set("char", "•")
    return tb

def table(sl, x, y, widths, rows, row_h=0.4, size=9.5, head_fg=TE, head_bg=TE_SOFT):
    """rows[0] is the header; cells are str or (bold_part, rest)."""
    gf = sl.shapes.add_table(len(rows), len(widths), IN(x), IN(y), IN(sum(widths)), IN(row_h * len(rows)))
    tbl = gf.table; tbl.first_row = True; tbl.horz_banding = False
    for j, w in enumerate(widths): tbl.columns[j].width = IN(w)
    for i, row in enumerate(rows):
        tbl.rows[i].height = IN(0.3 if i == 0 else row_h)
        for j, val in enumerate(row):
            c = tbl.cell(i, j); c.fill.solid()
            c.fill.fore_color.rgb = rgb(head_bg if i == 0 else ("FFFFFF" if i % 2 else CARD))
            c.margin_left = c.margin_right = IN(0.08); c.margin_top = c.margin_bottom = IN(0.03)
            c.vertical_anchor = MSO_ANCHOR.MIDDLE
            tf = c.text_frame; tf.word_wrap = True; p = tf.paragraphs[0]
            parts = [(val, i == 0 or j == 0)] if isinstance(val, str) else [(val[0], True), (val[1], False)]
            for t, b in parts:
                r = p.add_run(); r.text = t; f = r.font; f.name = FONT; f.size = Pt(size); f.bold = b
                f.color.rgb = rgb(head_fg if i == 0 else INK)
    return gf

# =====================================================================================
# SLIDE 2 — IDEA: problem + idea, pipeline, three pointer columns (bullets), two real phone screens
s = S[1]; set_team(s); set_title(s, "iTantra: Speak. Send Text. Hear It Back — Offline", 26)
remove(pointer_box(s))
LW = 8.45
label(s, 0.45, 1.3, LW, "Proposed Solution (Describe your Idea/Solution/Prototype)", size=13)
text(s, 0.45, 1.62, LW, 0.42, [[("Problem: ", {"bold": True, "color": SA}),
                               ("when towers fail after a flood or cyclone, only thin links remain — Bluetooth, a crowded hotspot or LoRa radio at ~250 bps. "
                                "Voice needs 256 kbps raw, ~12 kbps with a good codec.", {})]], size=10.5)
text(s, 0.45, 2.06, LW, 0.42, [[("Our idea: ", {"bold": True, "color": TE}),
                                ("send the meaning, not the sound. Speech becomes text on the phone, only the text travels, and it is spoken again on arrival. ", {}),
                                ("No tower, no internet, no cloud.", {"bold": True, "color": SA})]], size=10.5)
by = 2.62
steps = [("mic", SA, "Speak", "hold to talk"), ("wave", SA, "Speech → text", "on the phone"),
         ("bytes", SA, "Tiny frame", "≈62 bytes*"), ("wifi", INK, "Any link", "Wi-Fi · Direct · BLE"),
         ("speaker", TE, "Text → speech", "own language")]
sw = LW / 5
for i, (ic, col, t1, t2) in enumerate(steps):
    cx = 0.45 + i * sw + sw / 2
    icon_disc(s, ic, cx, by + 0.2, 0.4, col)
    text(s, cx - sw / 2, by + 0.45, sw, 0.4, [[(t1, {"bold": True, "size": 10, "color": col})], [(t2, {"size": 8.5, "color": MUTED})]], align=PP_ALIGN.CENTER)
    if i < 4: arrow(s, cx + 0.3, by + 0.2, cx + sw - 0.3, by + 0.2, color="B8BEC7", w=1.25)

cy = 3.6
cw, cg = 2.65, 0.25
xs = [0.45 + i * (cw + cg) for i in range(3)]
box(s, 0.45, cy - 0.08, LW, 0.01, fill=LINE)        # hairline separator under the pipeline
label(s, xs[0], cy, cw, "Detailed explanation of the proposed solution", size=10.5, h=0.4)
bullets(s, xs[0], cy + 0.48, cw, 2.6, [
    ("10 Indian languages. ", "Hindi, Bengali, Marathi, Telugu, Tamil, Gujarati, Kannada, Malayalam, Odia, English — speech in and out."),
    ("ALERT mode. ", "Plays on the alarm stream at full volume with vibration (16/16 in test)."),
    ("SOS. ", "One tap sends 1 of 6 templates with GPS/NavIC location, read aloud on arrival."),
    ("Talk your way. ", "Push-to-talk, hands-free call mode and live captions.")], lead=SA)
label(s, xs[1], cy, cw, "How it addresses the problem", size=10.5)
cd = CategoryChartData(); cd.categories = ["Raw audio", "Opus voice", "iTantra"]
cd.add_series("bps", (256000, 12000, 165))
gf = s.shapes.add_chart(XL_CHART_TYPE.BAR_CLUSTERED, IN(xs[1] - 0.05), IN(cy + 0.3), IN(cw + 0.05), IN(1.1), cd)
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
text(s, xs[1], cy + 1.4, cw, 0.2, [[("bits/s for one spoken sentence (log scale)", {"size": 8, "color": MUTED, "italic": True})]])
bullets(s, xs[1], cy + 1.68, cw, 1.4, [
    ("≈1,500× less data ", "than raw audio — a sentence fits LoRa's ~250 bps."),
    ("62 B ", "per sentence in binary vs 524 B as JSON."),
    ("Still speech at the end, ", "so people who cannot read are included.")], lead=SA)
label(s, xs[2], cy, cw, "Innovation and uniqueness of the solution", size=10.5, h=0.4)
bullets(s, xs[2], cy + 0.48, cw, 2.6, [
    ("Speech is the codec. ", "Text on the wire; the voice is rebuilt in the listener's language."),
    ("One frame, any link. ", "Wi-Fi, then Wi-Fi Direct (4 s), then Bluetooth LE (8 s), with no user action."),
    ("Trusted alerts. ", "ALERTs are Ed25519-signed; messages are encrypted after QR pairing."),
    ("Adapts to the phone. ", "LITE profile turns on by itself below 6 GB RAM.")], lead=TE)
footnote(s, 6.7, "*Median of 310 FLEURS sentences (unit test), before encryption and link headers. Bars assume a ~3 s sentence.", h=0.2, w=LW)

ph = 3.9; pw = ph * 540 / 1158
px1, px2 = 12.88 - 2 * pw - 0.15, 12.88 - pw
phone_pic(s, "img/03_sos_sheet.jpg", px1, 1.32, ph, url=DEMO)
phone_pic(s, "img/01_alert_card.jpg", px2, 1.32, ph, url=DEMO)
text(s, px1, 5.27, pw, 0.22, [[("Sender · SOS", {"bold": True, "size": 9.5, "color": SA})]], align=PP_ALIGN.CENTER)
text(s, px2, 5.27, pw, 0.22, [[("Receiver · ALERT", {"bold": True, "size": 9.5, "color": TE})]], align=PP_ALIGN.CENTER)
pill(s, px1, 5.62, 12.88 - px1, "film", "Watch the demo film  ·  1 min 55 s", DEMO, fg=SA, bg=SA_SOFT, size=9.5)
text(s, px1, 6.1, 12.88 - px1, 0.55, [[("Real screens from the running prototype (v0.1.0), vivo I2202, Snapdragon 870. ", {"size": 8.5, "color": MUTED}),
                                      ("Try it: itantra-106.pages.dev", {"size": 8.5, "color": TE, "bold": True, "link": SITE})]])

# =====================================================================================
# SLIDE 3 — TECHNICAL APPROACH: stack table, flow, status, real screens
s = S[2]; set_team(s); remove(pointer_box(s))
label(s, 0.45, 1.3, 4.0, "Technologies to be used (e.g. programming languages, frameworks, hardware)", size=11, h=0.45)
table(s, 0.45, 1.86, [1.15, 2.85], [
    ["Layer", "Technology"],
    ["App", "Kotlin 2.4, Jetpack Compose; Android 8.0+, 64-bit ARM"],
    ["Voice detection", "Silero VAD (0.6 MB) — a pause ends the sentence"],
    ["Speech-to-text", "SraVaani-1.0 (IISc), int8 ONNX on sherpa-onnx"],
    ["Text-to-speech", "VITS Rasa (6 languages), Indic-Mio via llama.cpp (4)"],
    ["Links", "Wi-Fi mDNS/TCP, Wi-Fi Direct, BLE GATT"],
    ["Security", "X25519, ChaCha20-Poly1305, Ed25519; QR pairing"],
    ["Radio", "ESP32-S3 + SX1262 LoRa, 865–867 MHz, RadioLib"],
    ["Control room", "Python gateway, SQLite log, CAP 1.2 alert parser"]], row_h=0.5, size=9)

label(s, 4.75, 1.3, 8.1, "Methodology and process for implementation (Flow Charts/Images/ working prototype)", size=11)
lanes = [("SENDER PHONE", SA, SA_SOFT, [("mic", "Mic 16 kHz"), ("ear", "VAD: cut at pause"), ("wave", "Speech-to-text"), ("bytes", "Pack · sign · encrypt")]),
         ("ANY LINK — same frame", INK, SLATE_SOFT, [("wifi", "Wi-Fi LAN (first)"), ("antenna", "Wi-Fi Direct (+4 s)"), ("bt", "Bluetooth LE (+8 s)"), ("server", "Control room / LoRa")]),
         ("RECEIVER PHONE", TE, TE_SOFT, [("check", "Verify · decode · ACK"), ("text", "Numbers → words"), ("speaker", "TTS, own language"), ("siren", "Voice note / ALERT")])]
ly, lh, lw = 1.66, 2.0, 2.55
for i, (title, col, soft, nodes) in enumerate(lanes):
    x = 4.75 + i * (lw + 0.22)
    box(s, x, ly, lw, lh, fill=soft, radius=0.06)
    text(s, x, ly + 0.07, lw, 0.2, [[(title, {"bold": True, "size": 9, "color": col})]], align=PP_ALIGN.CENTER)
    for j, (ic, t) in enumerate(nodes):
        ny = ly + 0.34 + j * 0.41
        box(s, x + 0.15, ny, lw - 0.3, 0.32, fill="FFFFFF", radius=0.3)
        icon(s, ic, "sa" if col == SA else ("te" if col == TE else "ink"), x + 0.26, ny + 0.06, 0.2)
        text(s, x + 0.54, ny, lw - 0.75, 0.32, [[(t, {"size": 9.5})]], anchor=MSO_ANCHOR.MIDDLE)
    if i < 2: arrow(s, x + lw + 0.03, ly + lh / 2, x + lw + 0.19, ly + lh / 2, color=INK, w=2)

sx = [4.75 + i * 2.77 for i in range(3)]
for x, (t, fg, d) in zip(sx, [("Built and running", GREEN, "Push-to-talk, call mode, 10-language STT/TTS, ALERT, SOS, LITE profile, control room."),
                              ("Partial · test pending", AMBER, "Encryption covers binary mode; LoRa firmware compiles; Wi-Fi Direct/BLE two-phone test."),
                              ("Planned", SLATE, "On-device translation (IndicTrans2), smaller voices, phone-to-phone mesh relay.")]):
    text(s, x, 3.82, 2.55, 0.8, [[(t, {"bold": True, "size": 10, "color": fg})], [(d, {"size": 9})]], spacing_after=2)

text(s, 4.75, 4.72, 8.1, 0.22, [[("Working prototype — real screens", {"bold": True, "size": 10})]])
sh_h, sy = 1.55, 5.0
x = 4.75
for pth, cap in [("img/dashboard.jpg", "PC control room"), ("img/pairing.jpg", "QR pairing"), ("img/08_hindi_ui.jpg", "LITE profile")]:
    if "dashboard" in pth:
        w = sh_h * 1376 / 632
        linkify(pic(s, pth, x, sy, w=w, h=sh_h, rounded=0.04), SITE)
    else:
        p = phone_pic(s, pth, x, sy, sh_h, url=SITE); w = p.width / 914400
    text(s, x - 0.1, sy + sh_h + 0.04, w + 0.2, 0.18, [[(cap, {"size": 8, "color": MUTED})]], align=PP_ALIGN.CENTER)
    x += w + 0.15
tx = x + 0.1
bullets(s, tx, sy, 12.88 - tx, 1.2, [("", "Prototype v0.1.0, APK 60 MB"), ("", "Models 1.2 GB, installed once"),
                                      ("", "Tested on Snapdragon 870 and 8 Gen 3")], size=9, gap=3)
pill(s, tx, sy + 1.25, 12.88 - tx, "download", "Reviewer install guide", SITE + "#try", size=9, h=0.3)

# =====================================================================================
# SLIDE 4 — FEASIBILITY: measured tiles, risk table, viability bullets, real metrics screen
s = S[3]; set_team(s); remove(pointer_box(s))
label(s, 0.45, 1.3, 6, "Analysis of the feasibility of the idea", size=14)
text(s, 6.0, 1.34, 6.88, 0.25, [[("Measured: 4 GB-class memory test, in-app logging", {"size": 10.5, "color": MUTED, "italic": True})]], align=PP_ALIGN.RIGHT)
tiles = [("14×", "faster than speech", "real-time factor 0.07, 10/10 languages"), ("0.6 s", "speech end → text", "median; range 0.42–0.70 s"),
         ("765 MB", "app RAM when idle", "1.8 GB peak while speaking"), ("0.3%", "idle CPU", "of one core, push-to-talk"),
         ("Alive", "app not killed", "Android closed 115 other processes")]
tw, tg = 2.35, 0.17
for i, (big, l1, l2) in enumerate(tiles):
    x = 0.45 + i * (tw + tg)
    box(s, x, 1.7, tw, 1.22, fill=TE_SOFT, radius=0.08)
    text(s, x + 0.18, 1.8, tw - 0.3, 0.5, [[(big, {"bold": True, "size": 24, "color": TE})]])
    text(s, x + 0.18, 2.3, tw - 0.3, 0.55, [[(l1, {"bold": True, "size": 10})], [(l2, {"size": 8.5, "color": MUTED})]])
footnote(s, 3.0, "Method: 12 GB vivo I2202 (Snapdragon 870) with RAM reserved so only ~1.7 GB stayed free, LITE profile; the chip was not slowed, so a budget phone will be slower. "
                  "Also measured: 41 ms message → ACK over Wi-Fi · 0 audio underruns in 5 languages · word error rate 5.9% (preliminary, 10 clips).", h=0.34)

table(s, 0.45, 3.56, [2.75, 4.45], [
    ["Potential challenges and risks", "Strategies for overcoming these challenges"],
    ["Voice starts 3–6 s after arrival in LITE", "Keep the listener's language loaded; add small, fast voices for hi/gu/or/en"],
    ["1.14 GB of speech models on the phone", "Download only the languages a user needs; installer checks every file's SHA-256"],
    ["Wi-Fi Direct / BLE: two-phone test pending", "Field tests on two phones; Wi-Fi stays the primary link; LoRa bridge for range"],
    ["Noise, accents and code-mixing", "Boost alert keywords; 30-clip-per-language evaluation; typed text as a fallback"]],
    row_h=0.56, size=9.5, head_fg=SA, head_bg=SA_SOFT)
text(s, 0.45, 6.2, 7.2, 0.45, [[("Next steps: ", {"bold": True, "size": 9.5, "color": TE}),
                                ("two-phone field test of every link · flash and range-test the LoRa bridge · 30-clip-per-language accuracy run · smaller voices for hi/gu/or/en.", {"size": 9.5})]])
label(s, 7.95, 3.52, 3.0, "Viability", size=12)
bullets(s, 7.95, 3.9, 3.05, 2.9, [
    ("₹0 per message: ", "no airtime, SIM, server or cloud fees."),
    ("Phones people own: ", "Android 8.0+; LITE mode for less RAM."),
    ("Low-cost range: ", "ESP32-S3 + LoRa node ≈ ₹1,900–3,200."),
    ("Open licences: ", "MIT, Apache-2.0 and CC-BY-4.0 models."),
    ("Fits alert systems: ", "the control room reads CAP 1.2 alert files.")], size=9.5, lead=TE, gap=6)
phone_pic(s, "img/07_metrics.jpg", 11.3, 3.52, 3.0)
text(s, 11.05, 6.58, 1.85, 0.2, [[("In-app metrics (real screen)", {"size": 8, "color": MUTED})]], align=PP_ALIGN.CENTER)

# =====================================================================================
# SLIDE 5 — IMPACT: real photos with who/why, stats, benefits as text columns, national alert fit
s = S[4]; set_team(s); remove(pointer_box(s))
label(s, 0.45, 1.3, 8, "Potential impact on the target audience", size=14)
aud = [("ndrf", "Disaster response teams", "Coordinate rescue by voice when towers and internet are down."),
       ("fish", "Coastal fishing communities", "Cyclone warnings spoken in their own language, with no data plan."),
       ("rural2", "Low-literacy users", "An alert they can hear instead of read, in 10 languages."),
       ("rail", "Remote field staff", "A voice link for teams working where coverage is poor.")]
cw, cg = 3.0, 0.14
for i, (key, t1, t2) in enumerate(aud):
    x = 0.45 + i * (cw + cg)
    fit_pic(s, f"photos/{key}.jpg", x, 1.66, cw, 1.6, url=credits[key]["page"])
    text(s, x, 3.34, cw, 0.62, [[(t1, {"bold": True, "size": 10.5})], [(t2, {"size": 9, "color": MUTED})]], spacing_after=1)
for i, ((big, l, col), bw) in enumerate(zip([("≈1,500×", "less data than raw audio*", SA), ("1.03 s", "ALERT arrival → spoken aloud (Wi-Fi)", TE),
                                             ("10", "Indian languages, speech in and out", INK)], (1.75, 1.2, 0.55))):
    x = 0.45 + i * 4.19
    text(s, x, 4.05, bw, 0.5, [[(big, {"bold": True, "size": 26, "color": col})]], anchor=MSO_ANCHOR.MIDDLE)
    text(s, x + bw + 0.08, 4.05, 3.95 - bw, 0.5, [[(l, {"size": 10.5})]], anchor=MSO_ANCHOR.MIDDLE)
box(s, 0.45, 4.66, 12.43, 0.01, fill=LINE)
label(s, 0.45, 4.76, 12, "Benefits of the solution (social, economic, environmental, etc.)", size=12.5)
for i, (ic, t1, items, col) in enumerate([
        ("heart", "Social", [("", "Alerts spoken aloud in Indian languages — people who cannot read are included."), ("", "SOS carries location to rescuers in one tap.")], SA),
        ("rupee", "Economic", [("", "No airtime, SIM, cloud or licence fees."), ("", "Runs on phones people own; a LoRa node costs ≈ ₹1,900–3,200.")], TE),
        ("leaf", "Environmental", [("", "Fewer bytes per message means less radio time on air."), ("", "No servers or data centres needed. Battery gain not yet measured.")], GREEN)]):
    x = 0.45 + i * 4.19
    icon_disc(s, ic, x + 0.17, 5.25, 0.34, col)
    text(s, x + 0.42, 5.13, 3.5, 0.26, [[(t1, {"bold": True, "size": 11, "color": col})]])
    bullets(s, x, 5.47, 3.95, 0.7, items, size=9.5, gap=2)
text(s, 0.45, 6.14, 12.4, 0.24, [[("Fits India's alert system: ", {"bold": True, "size": 9.5, "color": TE}),
                                  ("the control room reads CAP 1.2 alert files — the format behind ", {"size": 9.5}),
                                  ("NDMA SACHET", {"size": 9.5, "bold": True, "color": TE, "link": "https://sachet.ndma.gov.in/"}),
                                  (" — and re-broadcasts them as spoken alerts in each listener's language.", {"size": 9.5})]])
cr = credits
text(s, 0.45, 6.44, 12.4, 0.4, [[("*62 B per ~3 s sentence (unit-test median) vs 256 kbps raw audio. No field pilot yet.", {"size": 8, "color": MUTED, "italic": True})],
                                [("Photos, Wikimedia Commons: ", {"size": 8, "color": MUTED, "italic": True}),
                                 ("NDRF – MHA, GODL-India", {"size": 8, "color": TE, "italic": True, "link": cr["ndrf"]["page"]}), (" · ", {"size": 8, "color": MUTED}),
                                 (f"fishermen – {cr['fish']['artist']}, CC0", {"size": 8, "color": TE, "italic": True, "link": cr["fish"]["page"]}), (" · ", {"size": 8, "color": MUTED}),
                                 (f"woman on phone – {cr['rural2']['artist']}, CC BY-SA 3.0", {"size": 8, "color": TE, "italic": True, "link": cr["rural2"]["page"]}), (" · ", {"size": 8, "color": MUTED}),
                                 (f"rail workers – {cr['rail']['artist']}, CC BY-SA 2.0", {"size": 8, "color": TE, "italic": True, "link": cr["rail"]["page"]})]])

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
