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
# SLIDE 2 — IDEA: pipeline + two real phone screens + three pointer columns
s = S[1]; set_team(s); set_title(s, "iTantra: Speak. Send Text. Hear It Back — Offline", 26)
remove(pointer_box(s))
label(s, 0.45, 1.3, 8.0, "Proposed Solution (Describe your Idea/Solution/Prototype)", size=13)
text(s, 0.45, 1.62, 8.0, 0.3, [[("Only text crosses the link; the receiver hears speech. ", {}),
                               ("No tower. No internet. No cloud.", {"bold": True, "color": SA})]], size=12, color=MUTED)
by = 2.05
box(s, 0.45, by, 8.0, 1.3, fill=CARD, radius=0.1)
steps = [("mic", SA, "Speak"), ("wave", SA, "Speech → text"), ("bytes", SA, "≈62-byte frame*"),
         ("wifi", INK, "Wi-Fi · Direct · BLE"), ("speaker", TE, "Text → speech")]
sw = 8.0 / 5
for i, (ic, col, t1) in enumerate(steps):
    cx = 0.45 + i * sw + sw / 2
    icon_disc(s, ic, cx, by + 0.48, 0.58, col)
    text(s, cx - sw / 2, by + 0.85, sw, 0.3, [[(t1, {"bold": True, "size": 11, "color": col})]], align=PP_ALIGN.CENTER)
    if i < 4: arrow(s, cx + 0.4, by + 0.48, cx + sw - 0.4, by + 0.48, color="9AA3AF")

# real screens: sender and receiver
ph = 4.42
phone_pic(s, "img/03_sos_sheet.jpg", 8.62, 1.32, ph, url=DEMO)
phone_pic(s, "img/01_alert_card.jpg", 10.8, 1.32, ph, url=DEMO)
text(s, 8.62, 5.8, 2.06, 0.24, [[("Sender · SOS", {"bold": True, "size": 10, "color": SA})]], align=PP_ALIGN.CENTER)
text(s, 10.8, 5.8, 2.06, 0.24, [[("Receiver · ALERT", {"bold": True, "size": 10, "color": TE})]], align=PP_ALIGN.CENTER)
pill(s, 8.62, 6.18, 4.24, "film", "Watch the demo film  ·  1 min 55 s", DEMO, fg=SA, bg=SA_SOFT)

cy, chh, cw = 3.52, 3.1, 2.6
for i in range(3): box(s, 0.45 + i * (cw + 0.1), cy, cw, chh, fill="FFFFFF", line=LINE, radius=0.06)
label(s, 0.58, cy + 0.1, cw - 0.25, "Detailed explanation of the proposed solution", size=10.5, h=0.42)
for i, (ic, t) in enumerate([("langs", "10 Indian languages, on the phone"), ("siren", "ALERT at alarm volume"),
                             ("pin", "One-tap SOS with GPS/NavIC"), ("mic", "Push-to-talk and call mode")]):
    yy = cy + 0.72 + i * 0.58
    icon(s, ic, "sa", 0.6, yy, 0.3)
    text(s, 1.0, yy - 0.04, cw - 0.65, 0.5, [[(t, {"size": 10.5})]])
x2 = 0.45 + cw + 0.1
label(s, x2 + 0.13, cy + 0.1, cw - 0.25, "How it addresses the problem", size=10.5)
cd = CategoryChartData(); cd.categories = ["Raw audio", "Opus voice", "iTantra"]
cd.add_series("bps", (256000, 12000, 165))
gf = s.shapes.add_chart(XL_CHART_TYPE.BAR_CLUSTERED, IN(x2 + 0.05), IN(cy + 0.45), IN(cw - 0.12), IN(1.55), cd)
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
text(s, x2 + 0.13, cy + 2.02, cw - 0.25, 0.2, [[("bits/s per sentence (log scale)", {"size": 8.5, "color": MUTED, "italic": True})]])
text(s, x2 + 0.13, cy + 2.35, cw - 0.25, 0.6, [[("Thin links carry it.", {"bold": True, "size": 10.5, "color": SA})],
                                               [("Output is still speech.", {"bold": True, "size": 10.5, "color": TE})]])
x3 = 0.45 + 2 * (cw + 0.1)
label(s, x3 + 0.13, cy + 0.1, cw - 0.25, "Innovation and uniqueness of the solution", size=10.5, h=0.42)
for i, (ic, t) in enumerate([("layers", "Speech itself is the codec"), ("link", "Auto link fallback, one packet format"),
                             ("siren", "Alerts cannot be interrupted"), ("gauge", "LITE mode below 6 GB RAM")]):
    yy = cy + 0.72 + i * 0.58
    icon(s, ic, "te", x3 + 0.15, yy, 0.3)
    text(s, x3 + 0.55, yy - 0.04, cw - 0.65, 0.5, [[(t, {"size": 10.5})]])
footnote(s, 6.7, "*Median of 310 FLEURS sentences (unit test), before encryption and link headers. Bars assume a ~3 s sentence.", h=0.2, w=8.0)

# =====================================================================================
# SLIDE 3 — TECHNICAL APPROACH: stack + flow + real screens + status
s = S[2]; set_team(s); remove(pointer_box(s))
label(s, 0.45, 1.3, 3.9, "Technologies to be used (e.g. programming languages, frameworks, hardware)", size=11, h=0.45)
stack = [("android", "Android app", "Kotlin · Jetpack Compose"), ("ear", "Voice detection", "Silero VAD"),
         ("wave", "Speech-to-text", "SraVaani-1.0 · sherpa-onnx"), ("speaker", "Text-to-speech", "VITS Rasa · Indic-Mio"),
         ("wifi", "Links", "Wi-Fi · Wi-Fi Direct · BLE"), ("server", "Control room", "Python gateway · CAP 1.2")]
for i, (ic, t1, t2) in enumerate(stack):
    yy = 1.86 + i * 0.58
    box(s, 0.45, yy, 3.9, 0.5, fill=CARD, radius=0.2)
    icon_disc(s, ic, 0.76, yy + 0.25, 0.38, TE if i in (2, 3) else INK)
    text(s, 1.08, yy + 0.04, 3.2, 0.22, [[(t1, {"bold": True, "size": 10.5})]])
    text(s, 1.08, yy + 0.26, 3.2, 0.2, [[(t2, {"size": 9, "color": MUTED})]])
for i, (t, fg, bg, d) in enumerate([("BUILT", GREEN, GREEN_SOFT, "10-language STT/TTS, ALERT, SOS"),
                                    ("TEST PENDING", AMBER, AMBER_SOFT, "Wi-Fi Direct, BLE phone-to-phone"),
                                    ("PLANNED", SLATE, SLATE_SOFT, "ESP32 LoRa bridge, smaller voices")]):
    yy = 5.45 + i * 0.44
    chip(s, 0.45, yy, 1.3, t, fg, bg, size=8.5, h=0.3)
    text(s, 1.85, yy, 2.55, 0.3, [[(d, {"size": 9.5})]], anchor=MSO_ANCHOR.MIDDLE)

label(s, 4.6, 1.3, 8.3, "Methodology and process for implementation (Flow Charts/Images/ working prototype)", size=11)
lanes = [("SENDER PHONE", SA, SA_SOFT, [("mic", "Mic 16 kHz"), ("ear", "VAD: cut at pause"), ("wave", "STT (int8)"), ("bytes", "Compact frame")]),
         ("ANY LINK", INK, SLATE_SOFT, [("wifi", "Wi-Fi LAN"), ("antenna", "Wi-Fi Direct"), ("bt", "Bluetooth LE"), ("server", "PC control room")]),
         ("RECEIVER PHONE", TE, TE_SOFT, [("check", "Decode · ACK"), ("text", "Numbers → words"), ("speaker", "TTS"), ("siren", "Voice / ALERT")])]
ly, lh, lw = 1.66, 2.3, 2.62
for i, (title, col, soft, nodes) in enumerate(lanes):
    x = 4.6 + i * (lw + 0.21)
    box(s, x, ly, lw, lh, fill=soft, radius=0.07)
    text(s, x, ly + 0.07, lw, 0.22, [[(title, {"bold": True, "size": 9.5, "color": col})]], align=PP_ALIGN.CENTER)
    for j, (ic, t) in enumerate(nodes):
        ny = ly + 0.36 + j * 0.47
        box(s, x + 0.18, ny, lw - 0.36, 0.38, fill="FFFFFF", line=LINE, radius=0.25)
        icon(s, ic, "sa" if col == SA else ("te" if col == TE else "ink"), x + 0.3, ny + 0.07, 0.24)
        text(s, x + 0.64, ny, lw - 0.9, 0.38, [[(t, {"size": 10})]], anchor=MSO_ANCHOR.MIDDLE)
        if j < 3: arrow(s, x + lw / 2, ny + 0.38, x + lw / 2, ny + 0.47, color="9AA3AF", w=1.25)
    if i < 2: arrow(s, x + lw + 0.02, ly + lh / 2, x + lw + 0.19, ly + lh / 2, color=INK, w=2)

text(s, 4.6, 4.08, 8.3, 0.24, [[("Working prototype — real screens", {"bold": True, "size": 10.5})]])
sh_h, sy = 2.1, 4.38
shots = [("img/dashboard.jpg", "PC control room"), ("img/pairing.jpg", "QR pairing"),
         ("img/language_picker.jpg", "Language"), ("img/08_hindi_ui.jpg", "LITE profile")]
x = 4.6
for pth, cap in shots:
    if "dashboard" in pth:
        w = sh_h * 1376 / 632
        linkify(pic(s, pth, x, sy, w=w, h=sh_h, rounded=0.04, border=LINE), SITE)
    else:
        p = phone_pic(s, pth, x, sy, sh_h, url=SITE); w = p.width / 914400
    text(s, x, sy + sh_h + 0.04, w, 0.2, [[(cap, {"size": 8.5, "color": MUTED})]], align=PP_ALIGN.CENTER)
    x += w + 0.16

# =====================================================================================
# SLIDE 4 — FEASIBILITY: measured tiles, risks -> strategies, real metrics screen, viability
s = S[3]; set_team(s); remove(pointer_box(s))
label(s, 0.45, 1.3, 6, "Analysis of the feasibility of the idea", size=14)
text(s, 6.0, 1.34, 6.88, 0.25, [[("Measured: 4 GB-class memory test, in-app logging", {"size": 10.5, "color": MUTED, "italic": True})]], align=PP_ALIGN.RIGHT)
tiles = [("zap", "14×", "faster than speech"), ("clock", "0.6 s", "speech end → text"), ("memory", "765 MB", "app RAM, idle"),
         ("cpu", "0.3%", "idle CPU"), ("shield", "Alive", "app not killed")]
tw, tg = 2.35, 0.17
for i, (ic, big, l1) in enumerate(tiles):
    x = 0.45 + i * (tw + tg)
    box(s, x, 1.72, tw, 1.2, fill=CARD, radius=0.08)
    icon(s, ic, "te", x + 0.18, 1.84, 0.3)
    text(s, x + 0.18, 2.14, tw - 0.3, 0.48, [[(big, {"bold": True, "size": 26, "color": TE})]])
    text(s, x + 0.18, 2.6, tw - 0.3, 0.24, [[(l1, {"bold": True, "size": 10.5})]])
footnote(s, 3.0, "12 GB Snapdragon 870 phone, RAM locked to ~1.7 GB free, LITE profile, chip not slowed (a budget phone will be slower). "
                 "Median of 10 languages; 1.8 GB peak while speaking; 115 other apps were closed by Android. WER 5.9% (preliminary, 10 clips).", h=0.36)

label(s, 0.45, 3.55, 4.9, "Potential challenges and risks", size=12.5)
label(s, 5.75, 3.55, 5.0, "Strategies for overcoming these challenges", size=12.5)
risks = [("speaker", "Voice starts 3–6 s late in LITE", "Keep listener's language loaded; smaller voices"),
         ("database", "1.14 GB of model files", "Download per language on demand"),
         ("bt", "Wi-Fi Direct / BLE not yet tested", "Two-phone tests; Wi-Fi stays primary"),
         ("ear", "Noise, accents, code-mixing", "Alert-word boosting; typed-text fallback")]
for i, (ic, r, st) in enumerate(risks):
    yy = 3.9 + i * 0.56
    box(s, 0.45, yy, 10.45, 0.48, fill=CARD if i % 2 == 0 else "FFFFFF", radius=0.2)
    icon(s, ic, "sa", 0.62, yy + 0.1, 0.28)
    text(s, 1.05, yy, 4.3, 0.48, [[(r, {"bold": True, "size": 10.5})]], anchor=MSO_ANCHOR.MIDDLE)
    arrow(s, 5.2, yy + 0.24, 5.6, yy + 0.24, color=TE, w=1.5)
    text(s, 5.75, yy, 5.1, 0.48, [[(st, {"size": 10.5})]], anchor=MSO_ANCHOR.MIDDLE)
text(s, 0.45, 6.2, 1.2, 0.4, [[("Viability", {"bold": True, "size": 12})]], anchor=MSO_ANCHOR.MIDDLE)
for (ic, t, url), x, w in zip([("rupee", "No airtime or cloud fees", SITE), ("package", "Open models: MIT · Apache · CC-BY", MODELS),
                               ("github", "Open code and measurements", CODE)], (1.55, 4.55, 7.85), (2.85, 3.15, 3.05)):
    pill(s, x, 6.22, w, ic, t, url, size=9.5)
phone_pic(s, "img/07_metrics.jpg", 11.2, 3.55, 3.05)
text(s, 11.05, 6.64, 1.7, 0.2, [[("In-app metrics (real)", {"size": 8.5, "color": MUTED})]], align=PP_ALIGN.CENTER)

# =====================================================================================
# SLIDE 5 — IMPACT: real photos, 3 stats, benefits
s = S[4]; set_team(s); remove(pointer_box(s))
label(s, 0.45, 1.3, 8, "Potential impact on the target audience", size=14)
aud = [("ndrf", "lifebuoy", "Disaster response teams", "Towers and internet down"),
       ("fish", "ship", "Coastal fishing communities", "Weak links at sea"),
       ("rural2", "ear", "Low-literacy users", "Hear it, don't read it"),
       ("rail", "train", "Remote field staff", "Poor-coverage areas")]
cw, cg = 3.0, 0.14
for i, (key, ic, t1, t2) in enumerate(aud):
    x = 0.45 + i * (cw + cg)
    fit_pic(s, f"photos/{key}.jpg", x, 1.68, cw, 2.05, url=credits[key]["page"])
    icon(s, ic, "sa", x, 3.83, 0.28)
    text(s, x + 0.36, 3.8, cw - 0.36, 0.26, [[(t1, {"bold": True, "size": 11})]])
    text(s, x + 0.36, 4.06, cw - 0.36, 0.22, [[(t2, {"size": 9.5, "color": MUTED})]])
for i, ((big, l, col), bw) in enumerate(zip([("≈1,500×", "less data than raw audio*", SA), ("1.03 s", "ALERT arrival → spoken (Wi-Fi)", TE),
                                             ("10", "Indian languages, in and out", INK)], (1.9, 1.35, 0.6))):
    x = 0.45 + i * 4.19
    text(s, x, 4.45, bw, 0.6, [[(big, {"bold": True, "size": 30, "color": col})]], anchor=MSO_ANCHOR.MIDDLE)
    text(s, x + bw + 0.1, 4.45, 3.9 - bw, 0.6, [[(l, {"size": 11})]], anchor=MSO_ANCHOR.MIDDLE)
label(s, 0.45, 5.18, 12, "Benefits of the solution (social, economic, environmental, etc.)", size=13)
for i, (ic, t1, t2, col, soft) in enumerate([("heart", "Social", "Alerts heard, not read", SA, SA_SOFT),
                                              ("rupee", "Economic", "Phones people already own; no fees", TE, TE_SOFT),
                                              ("leaf", "Environmental", "Fewer bytes on air", GREEN, GREEN_SOFT)]):
    x = 0.45 + i * 4.19
    box(s, x, 5.55, 4.05, 0.8, fill=soft, radius=0.12)
    icon_disc(s, ic, x + 0.42, 5.95, 0.5, col)
    text(s, x + 0.8, 5.6, 3.1, 0.7, [[(t1, {"bold": True, "size": 11.5, "color": col})], [(t2, {"size": 10})]], anchor=MSO_ANCHOR.MIDDLE)
cr = credits
fn = text(s, 0.45, 6.45, 12.4, 0.4, [[("*62 B per ~3 s sentence (unit-test median) vs 256 kbps raw audio. No field pilot yet; battery gain not measured.", {"size": 8.5, "color": MUTED, "italic": True})],
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
              ("Silero VAD", "github.com/snakers4/silero-vad")]),
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
              ("Code and measurements", "github.com/prasadxa/itantra"),
              ("Website", "itantra-106.pages.dev"),
              ("Demo film", "itantra-106.pages.dev/demo"),
              ("Model pack", "r2.dev/models/v1/manifest.json")])]
full = {"r2.dev/models/v1/manifest.json": MODELS}
pos = [(0.45, 1.72, 3.2, 2.9), (3.75, 1.72, 3.2, 2.9), (7.05, 1.72, 3.2, 2.9), (0.45, 4.72, 4.85, 2.08), (5.4, 4.72, 4.85, 2.08)]
for (ic, title, col, refs), (x, y, w, h) in zip(groups, pos):
    box(s, x, y, w, h, fill=CARD, radius=0.05)
    icon_disc(s, ic, x + 0.3, y + 0.3, 0.4, col)
    text(s, x + 0.6, y + 0.17, w - 0.7, 0.28, [[(title, {"bold": True, "size": 11.5, "color": col})]])
    for j, (t, url) in enumerate(refs):
        yy = y + 0.6 + j * 0.45 if h > 2.5 else y + 0.58 + j * 0.37
        href = full.get(url, H + url)
        if h > 2.5:
            text(s, x + 0.2, yy, w - 0.3, 0.42, [[(t, {"size": 9.5, "bold": True, "link": href, "color": INK})],
                                                [(url, {"size": 7.5, "color": TE, "link": href})]])
        else:
            text(s, x + 0.2, yy, w - 0.3, 0.34, [[(t + "  ", {"size": 9.5, "bold": True, "link": href, "color": INK}),
                                                 (url, {"size": 8, "color": TE, "link": href})]])
qrs = [("img/qr-site.png", "Website", "overview · results", SITE), ("img/qr-demo.png", "Demo film", "1 min 55 s", DEMO),
       ("img/qr-github.png", "Code", "GitHub", CODE), ("img/qr-apk.png", "APK", "Android 8+", APK)]
for i, (q, t1, t2, url) in enumerate(qrs):
    y = 1.72 + i * 1.28
    linkify(box(s, 10.4, y, 2.48, 1.18, fill="FFFFFF", line=LINE, radius=0.08), url)
    linkify(pic(s, q, 10.5, y + 0.09, w=1.0, h=1.0), url)
    text(s, 11.6, y, 1.25, 1.18, [[(t1, {"bold": True, "size": 11, "link": url, "color": INK})], [(t2, {"size": 9, "color": MUTED})]], anchor=MSO_ANCHOR.MIDDLE)

prs.save("iTantra_SIH2026_v5.pptx")
print("saved")
