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

# =====================================================================================
# SLIDE 1 — title page: fill the template fields (values in a quieter weight/colour)
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
                    base = p.runs[-1]
                    if k == "PS Category":  # "PS Category- Software/Hardware" -> keep label, pick Software
                        for r in p.runs: r.text = r.text.replace("Software/Hardware", "").rstrip()
                    for lr in p.runs: lr.font.size = Pt(19)
                    r = p.add_run(); r.text = " " + v
                    r.font.name = FONT; r.font.bold = False; r.font.color.rgb = rgb(SA if k != "Problem Statement Title" else INK)
                    r.font.size = Pt(13 if k == "Problem Statement Title" else 17)
                    p.alignment = PP_ALIGN.LEFT; p.space_before = Pt(4); p.space_after = Pt(4); p.line_spacing = 1.0
                    break
# small brand lock-up under the SIH artwork: app logo + one-line promise + demo QR
pic(s, "img/logo.png", 7.62, 6.66, h=0.56, rounded=0.2)
text(s, 8.3, 6.64, 3.4, 0.62, [[("iTantra", {"bold": True, "size": 18, "color": INK})],
                             [("Speak. Send text. Hear it back — offline.", {"size": 11, "color": MUTED})]])

# =====================================================================================
# SLIDE 2 — IDEA: pipeline diagram + three pointer columns
s = S[1]; set_team(s); set_title(s, "iTantra: Speak. Send Text. Hear It Back — Offline", 26)
remove(pointer_box(s))
label(s, 0.45, 1.3, 9.5, "Proposed Solution (Describe your Idea/Solution/Prototype)", color=INK, size=14)
text(s, 0.45, 1.62, 9.6, 0.5, [[("An Android app that turns speech into text on the sender's phone, sends only the text, and speaks it aloud on the receiver's phone. ", {}),
                               ("No tower, no internet, no cloud.", {"bold": True, "color": SA})]], size=12, color=MUTED)
# demo QR (the PDF cannot play video)
pic(s, "img/qr-demo.png", 11.72, 1.28, w=1.05)
text(s, 9.7, 1.34, 1.95, 0.95, [[("Demo film", {"bold": True, "size": 11})], [("1 min 55 s", {"size": 10, "color": MUTED})],
                                [("itantra-106.pages.dev/demo", {"size": 8.5, "color": TE, "link": "https://itantra-106.pages.dev/demo/"})]], align=PP_ALIGN.RIGHT)

# pipeline band
by, bh = 2.38, 1.72
box(s, 0.45, by, 12.43, bh, fill=CARD, radius=0.08)
pic(s, "img/01_alert_card.jpg", 0.62, by + 0.12, h=1.48, rounded=0.12, border=LINE)           # sender phone (real screen)
pic(s, "img/07_history.jpg", 12.05, by + 0.12, h=1.48, rounded=0.12, border=LINE)            # receiver phone (real screen)
text(s, 0.3, by + bh + 0.02, 1.3, 0.2, [[("Sender", {"size": 9, "color": SA, "bold": True})]], align=PP_ALIGN.CENTER)
text(s, 11.75, by + bh + 0.02, 1.3, 0.2, [[("Receiver", {"size": 9, "color": TE, "bold": True})]], align=PP_ALIGN.CENTER)
steps = [("mic", SA, "1  Speak", "push-to-talk; a pause ends the sentence"),
         ("wave", SA, "2  Speech → text", "on-device STT, 10 languages"),
         ("bytes", SA, "3  Text frame", "median 62 bytes per sentence*"),
         ("wifi", INK, "4  Any link", "Wi-Fi → Wi-Fi Direct → Bluetooth LE"),
         ("speaker", TE, "5  Text → speech", "spoken aloud; ALERT at alarm volume")]
x0, step_w = 1.72, 2.03
for i, (ic, col, t1, t2) in enumerate(steps):
    cx = x0 + i * step_w + step_w / 2
    icon_disc(s, ic, cx, by + 0.52, 0.62, col)
    text(s, cx - step_w / 2 + 0.05, by + 0.9, step_w - 0.1, 0.28, [[(t1, {"bold": True, "size": 12, "color": col})]], align=PP_ALIGN.CENTER)
    text(s, cx - step_w / 2 + 0.1, by + 1.18, step_w - 0.2, 0.45, [[(t2, {"size": 9.5, "color": MUTED})]], align=PP_ALIGN.CENTER)
    if i < len(steps) - 1: arrow(s, cx + 0.42, by + 0.52, cx + step_w - 0.42, by + 0.52, color="9AA3AF")

# three pointer columns
cy, ch = 4.36, 2.33
cols = [(0.45, 4.0), (4.66, 4.0), (8.87, 4.01)]
for (x, w) in cols: box(s, x, cy, w, ch, fill="FFFFFF", line=LINE, radius=0.06)
label(s, 0.62, cy + 0.12, 3.7, "Detailed explanation of the proposed solution", size=11.5, h=0.45)
items = [("langs", "10 Indian languages, speech and voice, fully on the phone"),
         ("siren", "ALERT plays at alarm volume; one-tap SOS sends GPS/NavIC location"),
         ("mic", "Push-to-talk walkie-talkie and hands-free call mode")]
for i, (ic, t) in enumerate(items):
    yy = cy + 0.68 + i * 0.52
    icon(s, ic, "sa" if i != 2 else "ink", 0.66, yy, 0.3)
    text(s, 1.08, yy - 0.02, 3.25, 0.5, [[(t, {"size": 10.5})]])
label(s, 4.83, cy + 0.12, 3.7, "How it addresses the problem", size=11.5)
# native bar chart, log scale: bits per second for one ~3 s sentence
cd = CategoryChartData(); cd.categories = ["Raw audio", "Opus voice", "iTantra text"]
cd.add_series("bps", (256000, 12000, 165))
gf = s.shapes.add_chart(XL_CHART_TYPE.BAR_CLUSTERED, IN(4.72), IN(cy + 0.42), IN(3.86), IN(1.2), cd)
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
ca = ch_.category_axis; ca.tick_labels.font.size = Pt(9.5); ca.tick_labels.font.color.rgb = rgb(INK); ca.format.line.fill.background()
ca.reverse_order = True
text(s, 4.83, cy + 1.65, 3.7, 0.2, [[("bits per second, one spoken sentence (log scale)", {"size": 8.5, "color": MUTED, "italic": True})]])
text(s, 4.83, cy + 1.88, 3.7, 0.42, [[("Output is still speech", {"bold": True, "size": 10.5, "color": TE}), (" — people who cannot read are included", {"size": 10.5})]])
label(s, 9.04, cy + 0.12, 3.7, "Innovation and uniqueness of the solution", size=11.5, h=0.45)
inn = [("layers", "Speech → text → speech used as the codec for thin links"),
       ("siren", "Alerts re-voiced on arrival, cannot be interrupted"),
       ("link", "Auto link fallback, one packet format on every link"),
       ("gauge", "LITE profile switches on by itself below 6 GB RAM")]
for i, (ic, t) in enumerate(inn):
    yy = cy + 0.62 + i * 0.41
    icon(s, ic, "te", 9.08, yy, 0.27)
    text(s, 9.45, yy - 0.01, 3.3, 0.4, [[(t, {"size": 10})]])
footnote(s, 6.72, "*Median over 310 FLEURS sentences in a unit test; excludes encryption, alert signature and link headers. Bars assume a ~3 s sentence.", h=0.2)

# =====================================================================================
# SLIDE 3 — TECHNICAL APPROACH: stack cards + architecture flow + prototype strip
s = S[2]; set_team(s); remove(pointer_box(s))
label(s, 0.45, 1.3, 4.3, "Technologies to be used (e.g. programming languages, frameworks, hardware)", size=11.5, h=0.45)
stack = [("android", "Android app", "Kotlin · Jetpack Compose"),
         ("ear", "Voice detection", "Silero VAD"),
         ("wave", "Speech-to-text", "SraVaani-1.0 · int8 · sherpa-onnx"),
         ("speaker", "Text-to-speech", "VITS Rasa · Indic-Mio"),
         ("wifi", "Links", "Wi-Fi (mDNS/TCP) · Wi-Fi Direct · BLE"),
         ("phone", "Hardware", "Any Android phone · PC control room")]
for i, (ic, t1, t2) in enumerate(stack):
    yy = 1.86 + i * 0.66
    box(s, 0.45, yy, 4.15, 0.56, fill=CARD, radius=0.18)
    icon_disc(s, ic, 0.78, yy + 0.28, 0.4, INK if i != 3 else TE)
    text(s, 1.1, yy + 0.06, 3.4, 0.25, [[(t1, {"bold": True, "size": 11})]])
    text(s, 1.1, yy + 0.3, 3.4, 0.22, [[(t2, {"size": 9.5, "color": MUTED})]])

label(s, 4.9, 1.3, 8.0, "Methodology and process for implementation (Flow Charts/Images/ working prototype)", size=11.5)
lanes = [(4.9, "SENDER PHONE", SA, SA_SOFT, [("mic", "Mic 16 kHz"), ("ear", "VAD: cut at pause"), ("wave", "STT (int8)"), ("bytes", "Compact frame")]),
         (7.63, "LINK — same frame everywhere", INK, SLATE_SOFT, [("wifi", "Wi-Fi LAN (primary)"), ("antenna", "Wi-Fi Direct +4 s"), ("bt", "Bluetooth LE +8 s"), ("server", "PC control room")]),
         (10.36, "RECEIVER PHONE", TE, TE_SOFT, [("check", "Decode · ACK"), ("text", "Numbers → words"), ("speaker", "TTS engine"), ("siren", "Voice note / ALERT")])]
ly, lh, lw = 1.72, 2.55, 2.52
for i, (x, title, col, soft, nodes) in enumerate(lanes):
    box(s, x, ly, lw, lh, fill=soft, radius=0.07)
    text(s, x, ly + 0.08, lw, 0.24, [[(title, {"bold": True, "size": 9.5, "color": col})]], align=PP_ALIGN.CENTER)
    for j, (ic, t) in enumerate(nodes):
        ny = ly + 0.4 + j * 0.52
        box(s, x + 0.18, ny, lw - 0.36, 0.42, fill="FFFFFF", line=LINE, radius=0.25)
        icon(s, ic, "sa" if col == SA else ("te" if col == TE else "ink"), x + 0.3, ny + 0.08, 0.26)
        text(s, x + 0.65, ny, lw - 0.9, 0.42, [[(t, {"size": 10})]], anchor=MSO_ANCHOR.MIDDLE)
        if j < 3: arrow(s, x + lw / 2, ny + 0.42, x + lw / 2, ny + 0.52, color="9AA3AF", w=1.25)
    if i < 2: arrow(s, x + lw + 0.02, ly + lh / 2, x + lw + 0.19, ly + lh / 2, color=INK, w=2)

# working prototype: real screens + status
text(s, 4.9, 4.42, 3.0, 0.25, [[("Working prototype — real screens", {"bold": True, "size": 10.5})]])
shots = [("img/01_alert_card.jpg", "Talk + ALERT"), ("img/03_sos_sheet.jpg", "SOS + location"), ("img/07_metrics.jpg", "Live metrics")]
for i, (p, cap) in enumerate(shots):
    x = 4.9 + i * 1.12
    pic(s, p, x, 4.72, h=1.8, rounded=0.1, border=LINE)
    text(s, x - 0.05, 6.55, 0.92, 0.2, [[(cap, {"size": 8.5, "color": MUTED})]], align=PP_ALIGN.CENTER)
status = [("BUILT & RUNNING", GREEN, GREEN_SOFT, "Push-to-talk, call mode, 10-language STT/TTS, ALERT, SOS + location, LITE profile, compact frame, PC control room"),
          ("BUILT · TEST PENDING", AMBER, AMBER_SOFT, "Wi-Fi Direct and Bluetooth LE fallback, phone-to-phone"),
          ("PLANNED", SLATE, SLATE_SOFT, "Smaller voices (hi/gu/or/en), per-language model download, encrypted SOS location, ESP32 radio bridge")]
for i, (t, fg, bg, d) in enumerate(status):
    yy = 4.45 + i * 0.77
    chip(s, 8.3, yy, 1.85, t, fg, bg, size=8.5)
    text(s, 8.3, yy + 0.3, 4.58, 0.46, [[(d, {"size": 9.5})]])

# =====================================================================================
# SLIDE 4 — FEASIBILITY: measured stat tiles, risk -> strategy rows, viability chips
s = S[3]; set_team(s); remove(pointer_box(s))
label(s, 0.45, 1.3, 8, "Analysis of the feasibility of the idea", size=14)
text(s, 5.1, 1.34, 7.8, 0.25, [[("Measured in a 4 GB-class memory test (in-app logging)", {"size": 10.5, "color": MUTED, "italic": True})]], align=PP_ALIGN.RIGHT)
tiles = [("zap", "14×", "faster than speech", "STT real-time factor 0.07"),
         ("clock", "0.6 s", "speech end → text ready", "median, 10 languages"),
         ("memory", "765 MB", "app RAM when idle", "1.8 GB peak while speaking"),
         ("cpu", "0.3%", "idle CPU", "of one core"),
         ("shield", "Alive", "app not killed", "115 other apps closed")]
tw, tg = 2.35, 0.17
for i, (ic, big, l1, l2) in enumerate(tiles):
    x = 0.45 + i * (tw + tg)
    box(s, x, 1.72, tw, 1.42, fill=CARD, radius=0.08)
    icon(s, ic, "te", x + 0.18, 1.86, 0.32)
    text(s, x + 0.18, 2.2, tw - 0.3, 0.5, [[(big, {"bold": True, "size": 26, "color": TE})]])
    text(s, x + 0.18, 2.68, tw - 0.3, 0.22, [[(l1, {"bold": True, "size": 10})]])
    text(s, x + 0.18, 2.9, tw - 0.3, 0.2, [[(l2, {"size": 9, "color": MUTED})]])
footnote(s, 3.2, "4 GB-class test: 12 GB Snapdragon 870 phone with RAM locked so only ~1.7 GB was free, LITE profile. The chip was not slowed — a budget phone will be slower. Word error rate 5.9% (preliminary, 10 clips) unchanged.", h=0.36)

label(s, 0.45, 3.63, 5.6, "Potential challenges and risks", size=12.5)
label(s, 6.35, 3.63, 6.5, "Strategies for overcoming these challenges", size=12.5)
risks = [("speaker", "Voice starts 3–6 s after arrival in LITE", "Keep the listener's language loaded; smaller fast voices for hi/gu/or/en (planned)"),
         ("database", "1.14 GB of model files", "Per-language on-demand download and smaller voices for hi/gu/or/en (planned)"),
         ("bt", "Wi-Fi Direct / Bluetooth not yet tested phone-to-phone", "Two-phone tests on real devices; normal Wi-Fi stays the primary link"),
         ("ear", "Noise, accents, code-mixing raise errors", "Keyword boosting for alert words; 30-clip-per-language evaluation; typed text as fallback")]
for i, (ic, r, st) in enumerate(risks):
    yy = 3.98 + i * 0.58
    box(s, 0.45, yy, 12.43, 0.52, fill=CARD if i % 2 == 0 else "FFFFFF", radius=0.2)
    icon(s, ic, "sa", 0.62, yy + 0.11, 0.3)
    text(s, 1.05, yy, 4.9, 0.52, [[(r, {"bold": True, "size": 10.5})]], anchor=MSO_ANCHOR.MIDDLE)
    arrow(s, 5.75, yy + 0.26, 6.2, yy + 0.26, color=TE, w=1.5)
    text(s, 6.35, yy, 6.4, 0.52, [[(st, {"size": 10.5})]], anchor=MSO_ANCHOR.MIDDLE)
via = [("rupee", "No airtime, server or cloud fees"), ("package", "Open models: MIT · Apache-2.0 · CC-BY-4.0"), ("link", "One packet format on every link")]
text(s, 0.45, 6.37, 1.2, 0.36, [[("Viability", {"bold": True, "size": 12, "color": INK})]], anchor=MSO_ANCHOR.MIDDLE)
vx = [1.55, 5.05, 8.75]; vw = [3.35, 3.55, 4.13]
for (ic, t), x, w in zip(via, vx, vw):
    box(s, x, 6.37, w, 0.38, fill=TE_SOFT, radius=0.5)
    icon(s, ic, "te", x + 0.13, 6.43, 0.26)
    text(s, x + 0.46, 6.37, w - 0.55, 0.38, [[(t, {"size": 9.5, "bold": True, "color": TE})]], anchor=MSO_ANCHOR.MIDDLE)

# =====================================================================================
# SLIDE 5 — IMPACT: real photos of the four user groups, 3 stats, benefits
s = S[4]; set_team(s); remove(pointer_box(s))
label(s, 0.45, 1.3, 8, "Potential impact on the target audience", size=14)
aud = [("photos/ndrf.jpg", "lifebuoy", "Disaster response teams", "Voice coordination when towers and internet are down"),
       ("photos/fish.jpg", "ship", "Coastal fishing communities", "Spoken local-language alerts over weak links"),
       ("photos/rural2.jpg", "ear", "Low-literacy users", "Hear an alert a text message would miss"),
       ("photos/rail.jpg", "train", "Remote field staff", "Offline voice link in poor-coverage areas")]
from PIL import Image
cw, cg = 3.0, 0.14
for i, (ph, ic, t1, t2) in enumerate(aud):
    x = 0.45 + i * (cw + cg)
    im = Image.open(ph); ar = im.width / im.height; target = cw / 1.45
    p = s.shapes.add_picture(ph, IN(x), IN(1.7), IN(cw), IN(1.45))
    if ar > target:   # crop width
        c = (1 - target / ar) / 2; p.crop_left = c; p.crop_right = c
    else:
        c = (1 - ar / target) / 2; p.crop_top = c * 0.6; p.crop_bottom = c * 1.4
    box(s, x, 3.15, cw, 0.92, fill=CARD)
    icon(s, ic, "sa", x + 0.12, 3.26, 0.28)
    text(s, x + 0.48, 3.24, cw - 0.55, 0.3, [[(t1, {"bold": True, "size": 11})]])
    text(s, x + 0.12, 3.58, cw - 0.24, 0.45, [[(t2, {"size": 9.5, "color": MUTED})]])
stats = [("≈1,500×", "less data than raw audio*", SA), ("1.03 s", "ALERT arrival → spoken aloud (measured, Wi-Fi)", TE), ("10", "Indian languages, speech in and out, on the phone", INK)]
for i, ((big, l, col), bw) in enumerate(zip(stats, (2.05, 1.45, 0.75))):
    x = 0.45 + i * 4.19
    text(s, x, 4.2, bw, 0.62, [[(big, {"bold": True, "size": 30, "color": col})]], anchor=MSO_ANCHOR.MIDDLE)
    text(s, x + bw + 0.1, 4.2, 3.9 - bw, 0.62, [[(l, {"size": 10.5})]], anchor=MSO_ANCHOR.MIDDLE)
label(s, 0.45, 4.98, 12, "Benefits of the solution (social, economic, environmental, etc.)", size=13)
ben = [("heart", "Social", "Alerts spoken aloud in Indian languages — usable by people who cannot read", SA, SA_SOFT),
       ("rupee", "Economic", "No airtime, cloud or licence fees; runs on phones people already own", TE, TE_SOFT),
       ("leaf", "Environmental", "Fewer bytes per message means less radio airtime (battery gain not yet measured)", GREEN, GREEN_SOFT)]
for i, (ic, t1, t2, col, soft) in enumerate(ben):
    x = 0.45 + i * 4.19
    box(s, x, 5.35, 4.05, 1.08, fill=soft, radius=0.1)
    icon_disc(s, ic, x + 0.42, 5.35 + 0.54, 0.52, col)
    text(s, x + 0.8, 5.44, 3.1, 0.26, [[(t1, {"bold": True, "size": 11.5, "color": col})]])
    text(s, x + 0.8, 5.72, 3.15, 0.66, [[(t2, {"size": 9.5})]])
cr = credits
footnote(s, 6.5, "*62 B per ~3 s sentence vs 256 kbps raw audio; 62 B is a unit-test median. No field pilot yet.   Photos (Wikimedia Commons): NDRF – Ministry of Home Affairs, GODL-India; "
               f"fishermen – {cr['fish']['artist']}, CC0; woman on phone – {cr['rural2']['artist']}, CC BY-SA 3.0; rail workers – {cr['rail']['artist']}, CC BY-SA 2.0.", h=0.36)

# =====================================================================================
# SLIDE 6 — RESEARCH AND REFERENCES: grouped cards + QR codes
s = S[5]; set_team(s); remove(pointer_box(s))
label(s, 0.45, 1.3, 9, "Details / Links of the reference and research work", size=14)
groups = [("brain", "Open models (all run on the phone)", SA, [
              ("SraVaani-1.0 speech-to-text · ARTPARK, IISc · MIT", "huggingface.co/ARTPARK-IISc/SraVaani-1.0"),
              ("Indic-Mio text-to-speech · SPRING Lab, IIT Madras · Apache-2.0", "huggingface.co/SPRINGLab/Indic-Mio"),
              ("VITS Rasa 13 text-to-speech · AI4Bharat · CC-BY-4.0", "huggingface.co/ai4bharat/vits_rasa_13"),
              ("Silero VAD voice detection · MIT", "github.com/snakers4/silero-vad")]),
          ("database", "Runtime and evaluation data", TE, [
              ("sherpa-onnx on-device ASR/TTS runtime · Apache-2.0", "github.com/k2-fsa/sherpa-onnx"),
              ("Google FLEURS speech clips (STT evaluation)", "huggingface.co/datasets/google/fleurs")]),
          ("android", "Android platform guides", INK, [
              ("Wi-Fi Direct (Wi-Fi P2P)", "developer.android.com/develop/connectivity/wifi/wifi-direct"),
              ("Bluetooth and BLE GATT", "developer.android.com/develop/connectivity/bluetooth"),
              ("Audio focus (ALERT playback)", "developer.android.com/media/optimize/audio-focus")]),
          ("github", "Our work", GREEN, [
              ("Prototype code and measurements", "github.com/prasadxa/itantra"),
              ("Project website: overview, results, APK", "itantra-106.pages.dev"),
              ("Demo film, 1 min 55 s, captioned", "itantra-106.pages.dev/demo")])]
pos = [(0.45, 1.78, 5.45, 2.45), (6.05, 1.78, 4.35, 2.45), (0.45, 4.36, 5.45, 2.1), (6.05, 4.36, 4.35, 2.1)]
for (ic, title, col, refs), (x, y, w, h) in zip(groups, pos):
    box(s, x, y, w, h, fill=CARD, radius=0.05)
    icon_disc(s, ic, x + 0.33, y + 0.33, 0.44, col)
    text(s, x + 0.66, y + 0.18, w - 0.8, 0.3, [[(title, {"bold": True, "size": 12, "color": col})]])
    for j, (t, url) in enumerate(refs):
        yy = y + 0.66 + j * 0.44
        text(s, x + 0.25, yy, w - 0.4, 0.42, [[(t, {"size": 10, "bold": True})],
                                             [(url, {"size": 9, "color": TE, "link": "https://" + url})]])
for i, (q, t1, t2, url) in enumerate([("img/qr-site.png", "Website", "overview · results · APK", "https://itantra-106.pages.dev/"),
                                      ("img/qr-demo.png", "Demo film", "1 min 55 s", "https://itantra-106.pages.dev/demo/")]):
    y = 1.78 + i * 2.35
    box(s, 10.55, y, 2.33, 2.22 if i == 0 else 2.1, fill="FFFFFF", line=LINE, radius=0.06)
    pic(s, q, 11.07, y + 0.15, w=1.3)
    text(s, 10.6, y + 1.5, 2.23, 0.55, [[(t1, {"bold": True, "size": 11})], [(t2, {"size": 9, "color": MUTED})]], align=PP_ALIGN.CENTER)
footnote(s, 6.55, "Licences as stated on each model card. No cloud API is used for speech recognition or speech synthesis.", h=0.2)

prs.save("iTantra_SIH2026_v4.pptx")
print("saved")
