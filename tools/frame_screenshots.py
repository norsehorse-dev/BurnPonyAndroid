#!/usr/bin/env python3
"""Frame raw BurnPony screenshots into captioned store-marketing images.

Run from the repo root:

    python3 tools/frame_screenshots.py --platform android|ios [--repo .]
        [--font-dir DIR] [--only LOCALE] [--contact-sheet out.png]

Android
    in:  fastlane/metadata/android/<playLocale>/images/phoneScreenshots/<id>.png
    out: fastlane/framed/android/<playLocale>/<id>.png        (1080x1920)
iOS
    in:  fastlane/screenshots/<ascLocale>/<device>-<NN_id>.png
    out: fastlane/framed/ios/<ascLocale>/<NN_id>.png          (1320x2868)

Captions come from fastlane/captions/<appLang>.json, one key per screenshot
id (1_compose ... 5_sent, shared by both platforms). A value is either a
plain string (headline only) or {"title": "...", "sub": "..."}.

Needs Python 3.8+ and Pillow. No other dependencies.
"""

import argparse
import json
import os
import sys
import unicodedata

try:
    from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageChops
except ImportError:  # pragma: no cover
    sys.exit("error: Pillow is required (pip3 install pillow)")


# --------------------------------------------------------------------------
# Constants
# --------------------------------------------------------------------------

SCREEN_IDS = ["1_compose", "2_double_bottom", "3_options", "4_share", "5_sent"]

# app language code -> (Play locale, App Store Connect locale)
LOCALES = [
    ("en", "en-US", "en-US"),
    ("de", "de-DE", "de-DE"),
    ("es", "es-ES", "es-ES"),
    ("fr", "fr-FR", "fr-FR"),
    ("it", "it-IT", "it"),
    ("ja", "ja-JP", "ja"),
    ("pt-BR", "pt-BR", "pt-BR"),
    ("ru", "ru-RU", "ru"),
    ("zh-Hans", "zh-CN", "zh-Hans"),
]

CANVAS = {"android": (1080, 1920), "ios": (1320, 2868)}

MIN_INPUT_BYTES = 40 * 1024
ASPECTS = (9 / 16, 9 / 19.5)  # width / height
ASPECT_TOLERANCE = 0.045

# Ember palette (BurnPonyTheme.kt / BurnPonyTheme.swift)
BG_TOP = (16, 13, 19)       # deep charcoal, a touch lifter than #050408
BG_BOTTOM = (60, 24, 15)    # dark ember: ember #F67529 / emberDeep pulled into the dark
EMBER = (246, 117, 41)      # #F67529
TITLE_INK = (255, 247, 238)  # near-white, warmed toward ink #FFF0DD
SUB_INK = (191, 178, 164)    # dim #AFA294, lifted for legibility
BEZEL = (12, 11, 15)
EDGE_TOP = (78, 73, 84)
EDGE_BOTTOM = (40, 37, 45)

# Layout, as fractions of canvas width unless noted
MARGIN_X = 0.085
MARGIN_TOP = 0.085
TITLE_MAX = 0.080
TITLE_MIN = 0.042
SUB_MAX = 0.040
SUB_MIN = 0.028
GAP_TITLE_SUB = 0.042
GAP_TEXT_PHONE = 0.062
MARGIN_BOTTOM = 0.062
PHONE_MAX_W = 0.78
BEZEL_FRAC = 0.028     # of phone width
EDGE_FRAC = 0.0045     # of phone width
CORNER_FRAC = 0.13     # outer corner radius, of phone width
CORNER_FRAC_16x9 = 0.06  # 16:9 captures (Android) put the clock at the very
                         # edge of the status bar; a tight radius keeps it whole

MAC_FONTS = "/System/Library/Fonts"
MAC_SUPP = "/System/Library/Fonts/Supplemental"


class FrameError(Exception):
    pass


def warn(msg):
    print("warning: " + msg, file=sys.stderr)


# --------------------------------------------------------------------------
# Fonts
# --------------------------------------------------------------------------

class FontSpec:
    """A font file plus how to pick the face (ttc index / variation)."""

    def __init__(self, path, family=None, styles=None, axes=None, index=None):
        self.path = path
        self.family = family      # substring the face family name must contain
        self.styles = styles      # acceptable style substrings, in preference order
        self.axes = axes          # {"wght": 700, "opsz": "max"} for variable fonts
        self.index = index
        self._resolved = None
        self._cache = {}

    def describe(self):
        s = self.path
        if self.index:
            s += " #%d" % self.index
        if self.axes:
            s += " " + ",".join("%s=%s" % kv for kv in sorted(self.axes.items()))
        return s

    def resolve(self):
        """Find the ttc index; check variations. Returns False if unusable."""
        if self._resolved is not None:
            return self._resolved
        self._resolved = False
        if not os.path.isfile(self.path):
            return False
        if self.index is None:
            idx = self._find_index()
            if idx is None:
                return False
            self.index = idx
        try:
            self._load(48)
        except (OSError, ValueError):
            return False  # unreadable file, or no variable-font support
        self._resolved = True
        return True

    def _find_index(self):
        if not self.family and not self.styles:
            return 0
        faces = []
        for i in range(64):
            try:
                f = ImageFont.truetype(self.path, 20, index=i)
            except OSError:
                break
            fam, sty = f.getname()
            faces.append((i, fam or "", sty or ""))
        if not faces:
            return None
        cands = [x for x in faces if not self.family or self.family.lower() in x[1].lower()]
        if not cands:
            return None
        if not self.styles:
            return cands[0][0]
        for want in self.styles:
            for i, fam, sty in cands:
                if want.lower() == sty.lower() or want.lower() in fam.lower().split():
                    return i
        for want in self.styles:
            for i, fam, sty in cands:
                if want.lower() in sty.lower():
                    return i
        return None

    def _load(self, size):
        f = ImageFont.truetype(self.path, size, index=self.index or 0)
        if self.axes:
            axes = f.get_variation_axes()
            values = []
            for ax in axes:
                name = ax.get("name", b"")
                if isinstance(name, bytes):
                    name = name.decode("latin-1", "replace")
                name = name.lower()
                lo, hi, val = ax["minimum"], ax["maximum"], ax["default"]
                for key, want in self.axes.items():
                    tag = {"wght": "weight", "opsz": "optical", "wdth": "width"}.get(key, key)
                    if tag in name or name == key:
                        if want == "max":
                            val = hi
                        elif want == "min":
                            val = lo
                        else:
                            val = max(lo, min(hi, want))
                values.append(val)
            f.set_variation_by_axes(values)
        return f

    def font(self, size):
        size = int(round(size))
        f = self._cache.get(size)
        if f is None:
            f = self._load(size)
            self._cache[size] = f
        return f


def mac_font_candidates():
    hira = lambda w: os.path.join(MAC_FONTS, "ヒラギノ角ゴシック W%d.ttc" % w)
    latin_bold = [
        FontSpec(os.path.join(MAC_FONTS, "SFNS.ttf"), axes={"wght": 700, "opsz": "max"}),
        FontSpec(os.path.join(MAC_SUPP, "Arial Bold.ttf")),
        FontSpec(os.path.join(MAC_FONTS, "Helvetica.ttc"), family="Helvetica", styles=["Bold"]),
        FontSpec(os.path.join(MAC_SUPP, "Arial Unicode.ttf")),
    ]
    latin_reg = [
        FontSpec(os.path.join(MAC_FONTS, "SFNS.ttf"), axes={"wght": 500, "opsz": "max"}),
        FontSpec(os.path.join(MAC_SUPP, "Arial.ttf")),
        FontSpec(os.path.join(MAC_FONTS, "Helvetica.ttc"), family="Helvetica", styles=["Regular"]),
        FontSpec(os.path.join(MAC_SUPP, "Arial Unicode.ttf")),
    ]
    ja_bold = [
        FontSpec(hira(6), family="Hiragino Sans"),
        FontSpec(hira(6)),
        FontSpec(hira(7)),
        FontSpec(hira(5)),
        FontSpec(os.path.join(MAC_SUPP, "Arial Unicode.ttf")),
    ]
    ja_reg = [
        FontSpec(hira(3), family="Hiragino Sans"),
        FontSpec(hira(3)),
        FontSpec(hira(4)),
        FontSpec(os.path.join(MAC_SUPP, "Arial Unicode.ttf")),
    ]
    zh_bold = [
        FontSpec(os.path.join(MAC_FONTS, "Hiragino Sans GB.ttc"), styles=["W6", "Bold"]),
        FontSpec(os.path.join(MAC_FONTS, "PingFang.ttc"), family="PingFang SC", styles=["Semibold", "Medium"]),
        FontSpec(os.path.join(MAC_FONTS, "STHeiti Medium.ttc"), family="SC"),
        FontSpec(os.path.join(MAC_FONTS, "STHeiti Medium.ttc")),
        FontSpec(os.path.join(MAC_SUPP, "Arial Unicode.ttf")),
    ]
    zh_reg = [
        FontSpec(os.path.join(MAC_FONTS, "Hiragino Sans GB.ttc"), styles=["W3", "Regular"]),
        FontSpec(os.path.join(MAC_FONTS, "PingFang.ttc"), family="PingFang SC", styles=["Regular"]),
        FontSpec(os.path.join(MAC_FONTS, "STHeiti Light.ttc"), family="SC"),
        FontSpec(os.path.join(MAC_FONTS, "STHeiti Light.ttc")),
        FontSpec(os.path.join(MAC_SUPP, "Arial Unicode.ttf")),
    ]
    return {
        "latin": (latin_bold, latin_reg),
        "ja": (ja_bold, ja_reg),
        "zh": (zh_bold, zh_reg),
    }


def dir_font_candidates(font_dir):
    if not os.path.isdir(font_dir):
        raise FrameError("--font-dir %s is not a directory" % font_dir)
    files = {}
    for root, _dirs, names in os.walk(font_dir):
        for n in names:
            if n.lower().endswith((".ttf", ".otf", ".ttc")):
                files.setdefault(n.lower(), os.path.join(root, n))

    def pick(names, **kw):
        out = []
        for n in names:
            p = files.get(n.lower())
            if p:
                out.append(FontSpec(p, **kw))
        return out

    def variable(prefix, wght):
        out = []
        for n, p in sorted(files.items()):
            if n.startswith(prefix.lower() + "[") and "wght" in n:
                out.append(FontSpec(p, axes={"wght": wght}))
        return out

    latin_bold = pick(["NotoSans-Bold.ttf", "NotoSans-Bold.otf", "NotoSans-SemiBold.ttf",
                       "NotoSansDisplay-Bold.ttf"]) + variable("NotoSans", 700)
    latin_reg = pick(["NotoSans-Medium.ttf", "NotoSans-Regular.ttf", "NotoSans-Regular.otf"]) \
        + variable("NotoSans", 500)
    cjk_b = ["NotoSansCJK-Bold.ttc", "NotoSansCJKsc-Bold.otf", "NotoSansCJKjp-Bold.otf"]
    cjk_r = ["NotoSansCJK-Regular.ttc", "NotoSansCJK-DemiLight.ttc",
             "NotoSansCJKsc-Regular.otf", "NotoSansCJKjp-Regular.otf"]
    ja_bold = pick(cjk_b, family="CJK JP") + pick(["NotoSansJP-Bold.otf", "NotoSansJP-Bold.ttf"]) \
        + variable("NotoSansJP", 700)
    ja_reg = pick(cjk_r, family="CJK JP") + pick(["NotoSansJP-Regular.otf", "NotoSansJP-Regular.ttf"]) \
        + variable("NotoSansJP", 400)
    zh_bold = pick(cjk_b, family="CJK SC") + pick(["NotoSansSC-Bold.otf", "NotoSansSC-Bold.ttf"]) \
        + variable("NotoSansSC", 700)
    zh_reg = pick(cjk_r, family="CJK SC") + pick(["NotoSansSC-Regular.otf", "NotoSansSC-Regular.ttf"]) \
        + variable("NotoSansSC", 400)
    return {
        "latin": (latin_bold, latin_reg),
        "ja": (ja_bold, ja_reg),
        "zh": (zh_bold, zh_reg),
    }


_TOFU_PROBES = ("\U0010FFFD", "\uFFFF", "\U000E01EF")


def _glyph_sig(font, ch):
    mask = font.getmask(ch)
    return mask.size, bytes(mask)


def missing_glyphs(spec, text):
    """Rough coverage check: a char is missing if it renders like .notdef
    (tofu) or renders blank when it should not."""
    f = spec.font(40)
    tofu = set()
    for p in _TOFU_PROBES:
        try:
            tofu.add(_glyph_sig(f, p))
        except Exception:
            pass
    missing = []
    for ch in sorted(set(text)):
        if ch.isspace() or unicodedata.category(ch) in ("Mn", "Me", "Cf", "Cc"):
            continue
        try:
            sig = _glyph_sig(f, ch)
        except Exception:
            missing.append(ch)
            continue
        size, data = sig
        blank = size[0] == 0 or size[1] == 0 or not any(data)
        if sig in tofu or blank:
            missing.append(ch)
    return missing


def choose_font(cands, text, role, script):
    tried = []
    for spec in cands:
        try:
            ok = spec.resolve()
        except OSError as e:
            tried.append("%s (%s)" % (spec.describe(), e))
            continue
        if not ok:
            tried.append("%s (not found or unusable)" % spec.describe())
            continue
        miss = missing_glyphs(spec, text)
        if miss:
            tried.append("%s (missing %s)" % (spec.describe(), "".join(miss[:20])))
            continue
        return spec
    raise FrameError("no %s font for %s text can render the captions; tried:\n    %s"
                     % (role, script, "\n    ".join(tried) or "(no candidates)"))


def script_for(app_lang):
    if app_lang == "ja":
        return "ja"
    if app_lang.startswith("zh"):
        return "zh"
    return "latin"


# --------------------------------------------------------------------------
# Line breaking
# --------------------------------------------------------------------------

# Kinsoku: characters that must not start a line / must not end a line.
NO_START = set("、。，．・：；？！ー」』）】〉》〕｝〙〗〟’”»?!:;,.%)]}…‥々ゝゞヽヾぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮヵヶ")
NO_END = set("「『（【〈《〔｛〘〖〝‘“«([{")


def _is_cjk(ch):
    o = ord(ch)
    return (0x3000 <= o <= 0x30FF or 0x3400 <= o <= 0x4DBF or 0x4E00 <= o <= 0x9FFF
            or 0xF900 <= o <= 0xFAFF or 0xFF00 <= o <= 0xFFEF or 0x20000 <= o <= 0x2FFFF)


def _kind(ch):
    o = ord(ch)
    if 0x3040 <= o <= 0x309F:
        return "hira"
    if 0x30A0 <= o <= 0x30FF or 0x31F0 <= o <= 0x31FF:
        return "kata"
    if _is_cjk(ch):
        return "han"
    return "latin"


CJK_GOOD_AFTER = set("、。，．・：；！？」』）】〉》")
ZH_GOOD_AFTER = set("的了和与及或")


def break_positions(text):
    """(index, good) pairs where a line may break. Breaking at i means
    line1 = text[:i].rstrip(), line2 = text[i:].lstrip(). "good" breaks
    fall on spaces, punctuation or likely phrase boundaries; other CJK
    breaks are legal but only used when nothing better fits."""
    pos = []
    n = len(text)
    for i in range(1, n):
        prev, cur = text[i - 1], text[i]
        if prev == " " and cur != " ":
            # break after a space, unless that strands French-style punctuation
            j = i - 2
            while j >= 0 and text[j] == " ":
                j -= 1
            if cur in "?!:;»%" or (j >= 0 and text[j] in "«"):
                continue
            pos.append((i, True))
        elif prev in "-\u2010\u2013\u2014/" and cur != " " and i > 1 and text[i - 2] != " " \
                and not _is_cjk(cur):
            pos.append((i, True))  # after a hyphen inside a compound
        elif (_is_cjk(prev) or _is_cjk(cur)) and prev != " " and cur != " ":
            if cur in NO_START or prev in NO_END:
                continue
            # keep Latin words / numbers whole
            if prev.isascii() and cur.isascii():
                continue
            kp, kc = _kind(prev), _kind(cur)
            good = (prev in CJK_GOOD_AFTER
                    or (kp == "hira" and kc in ("han", "kata"))   # particle -> next phrase
                    or (kp == "latin") != (kc == "latin")         # script change
                    or (prev in ZH_GOOD_AFTER and kc == "han" and not _has_kana(text)))
            pos.append((i, good))
    return pos


def _has_kana(text):
    return any(_kind(c) in ("hira", "kata") for c in text)


def text_width(font, s):
    if not s:
        return 0
    l, _t, r, _b = font.getbbox(s)
    return r - l


def wrap(font, text, max_w, max_lines=2, good_only=False):
    """Return a balanced list of <= max_lines lines that fit, or None."""
    forced = [p.strip() for p in text.split("\n")]
    if len(forced) > max_lines:
        return None
    if len(forced) == max_lines:
        return forced if all(text_width(font, s) <= max_w for s in forced) else None
    t = forced[0]
    if text_width(font, t) <= max_w:
        return [t]
    if max_lines < 2:
        return None
    best = None
    for p, good in break_positions(t):
        if good_only and not good:
            continue
        a, b = t[:p].rstrip(), t[p:].lstrip()
        if not a or not b:
            continue
        wa, wb = text_width(font, a), text_width(font, b)
        if wa > max_w or wb > max_w:
            continue
        # balance, with a slight preference for a longer first line; a
        # mid-phrase CJK break costs a lot
        score = max(wa, wb) + (0.02 * max_w if wb > wa else 0) + (0 if good else 0.5 * max_w)
        if best is None or score < best[0]:
            best = (score, [a, b])
    return best[1] if best else None


def fit_size(spec, text, max_w, size_max, size_min, max_lines=2):
    """Largest size at which the text fits in max_lines. A clean break
    (space, punctuation, phrase boundary) is worth shrinking up to ~20%
    for; past that, any legal break is accepted."""
    for good_only, floor in ((True, max(size_min, size_max * 0.8)), (False, size_min)):
        size = size_max
        while size >= floor:
            if wrap(spec.font(size), text, max_w, max_lines, good_only):
                return int(round(size))
            size *= 0.97
    return None


# --------------------------------------------------------------------------
# Drawing helpers
# --------------------------------------------------------------------------

def rounded_mask(w, h, r, ss=4):
    """Anti-aliased rounded-rectangle mask (L). Only the corners are
    supersampled, so this stays cheap at App Store sizes."""
    w, h = int(w), int(h)
    r = max(1, min(int(round(r)), w // 2, h // 2))
    mask = Image.new("L", (w, h), 255)
    big = Image.new("L", (r * ss, r * ss), 0)
    ImageDraw.Draw(big).ellipse((0, 0, 2 * r * ss - 1, 2 * r * ss - 1), fill=255)
    corner = big.resize((r, r), Image.LANCZOS)
    mask.paste(corner, (0, 0))
    mask.paste(corner.transpose(Image.FLIP_LEFT_RIGHT), (w - r, 0))
    mask.paste(corner.transpose(Image.FLIP_TOP_BOTTOM), (0, h - r))
    mask.paste(corner.transpose(Image.ROTATE_180), (w - r, h - r))
    return mask


def vertical_gradient(w, h, top, bottom, gamma=1.0):
    col = Image.new("RGB", (1, h))
    px = []
    for y in range(h):
        t = (y / max(1, h - 1)) ** gamma
        px.append(tuple(int(round(top[i] + (bottom[i] - top[i]) * t)) for i in range(3)))
    col.putdata(px)
    return col.resize((w, h), Image.NEAREST)


def radial_glow(w, h, cx, cy, rx, ry, peak):
    """L mask of a soft elliptical glow, peak in 0..1."""
    lut = [int(round(255 * peak * max(0.0, 1 - v / 255.0) ** 2.2)) for v in range(256)]
    g = Image.radial_gradient("L").point(lut)  # 256x256, centre = 0
    g = g.resize((max(1, int(2 * rx)), max(1, int(2 * ry))), Image.BICUBIC)
    m = Image.new("L", (w, h), 0)
    m.paste(g, (int(cx - rx), int(cy - ry)))
    return m


_NOISE_TILE = None


def dither(img, sigma=1.6, tile=256):
    """Add fixed, seeded grain so the gradient does not band. Seeded so
    re-running the tool gives byte-identical output (no git churn)."""
    global _NOISE_TILE
    if _NOISE_TILE is None:
        import random
        rnd = random.Random(0xB0B9)
        data = bytes(max(0, min(255, int(round(128 + rnd.gauss(0, sigma)))))
                     for _ in range(tile * tile))
        _NOISE_TILE = Image.frombytes("L", (tile, tile), data)
    noise = Image.new("L", img.size)
    for y in range(0, img.size[1], tile):
        for x in range(0, img.size[0], tile):
            noise.paste(_NOISE_TILE, (x, y))
    noise3 = Image.merge("RGB", (noise, noise, noise))
    return ImageChops.add(img, noise3, 1.0, -128)


# --------------------------------------------------------------------------
# Composition
# --------------------------------------------------------------------------

class TextBlock:
    def __init__(self, spec, size, lines, line_h, ref):
        self.spec = spec
        self.size = size
        self.lines = lines
        self.line_h = line_h
        self.ref_top, self.ref_bottom = ref

    def height(self, n=None):
        n = len(self.lines) if n is None else n
        return (n - 1) * self.line_h + (self.ref_bottom - self.ref_top)

    def draw(self, draw, cx, top, fill):
        f = self.spec.font(self.size)
        y = top - self.ref_top  # first baseline
        for line in self.lines:
            draw.text((cx, y), line, font=f, fill=fill, anchor="ms")
            y += self.line_h


def make_block(spec, size, text, max_w, script, leading):
    f = spec.font(size)
    lines = wrap(f, text, max_w)
    if not lines:
        raise FrameError("caption does not fit at %dpx: %r" % (size, text))
    ref_chars = "国H" if script != "latin" else "Hx"
    t, b = 0, 0
    for ch in ref_chars:
        bb = f.getbbox(ch, anchor="ls")
        t, b = min(t, bb[1]), max(b, bb[3])
    if script == "latin":
        b = 0  # sit on the baseline; descenders are allowed to hang
    lead = leading * (1.12 if script != "latin" else 1.0)
    return TextBlock(spec, size, lines, int(round(size * lead)), (t, b))


def phone_geometry(canvas_w, area_top, area_bottom, shot_w, shot_h):
    """Phone outer box + screen box sized to the screenshot aspect."""
    avail_h = area_bottom - area_top
    max_pw = canvas_w * PHONE_MAX_W
    aspect = shot_h / shot_w
    # pw = sw + 2b, b = BEZEL_FRAC * pw  ->  sw = pw * (1 - 2 BEZEL_FRAC)
    k = 1 - 2 * BEZEL_FRAC
    ph_per_pw = k * aspect + 2 * BEZEL_FRAC
    pw = min(max_pw, avail_h / ph_per_pw)
    b = round(BEZEL_FRAC * pw)
    sw = int(round(pw - 2 * b))
    sh = int(round(sw * aspect))
    pw, ph = sw + 2 * b, sh + 2 * b
    x = (canvas_w - pw) // 2
    # Anchor to the bottom margin: when the phone is width-capped, the spare
    # room goes to the headline band instead of opening a gap under the text.
    y = area_bottom - ph
    return x, y, pw, ph, b


def draw_phone(canvas, shot, x, y, pw, ph, b):
    W = canvas.size[0]
    R = (CORNER_FRAC if shot.height / shot.width > 1.9 else CORNER_FRAC_16x9) * pw
    outer = rounded_mask(pw, ph, R)

    # Drop shadow: a wide soft one plus a tighter contact shadow.
    for blur, dy, opacity in ((0.045 * W, 0.022 * W, 0.62),
                              (0.012 * W, 0.006 * W, 0.45)):
        pad = int(blur * 3) + 4
        sh = Image.new("L", (pw + 2 * pad, ph + 2 * pad), 0)
        sh.paste(outer, (pad, pad))
        sh = sh.filter(ImageFilter.GaussianBlur(blur))
        sh = sh.point(lambda v, o=opacity: int(v * o))
        layer = Image.new("RGB", sh.size, (0, 0, 0))
        region = (x - pad, int(y - pad + dy))
        canvas.paste(layer, region, sh)

    # Thin lighter edge (subtle top-lit gradient), then the near-black body.
    edge = vertical_gradient(pw, ph, EDGE_TOP, EDGE_BOTTOM, gamma=0.8)
    canvas.paste(edge, (x, y), outer)
    e = max(2, int(round(EDGE_FRAC * pw)))
    body_mask = rounded_mask(pw - 2 * e, ph - 2 * e, R - e)
    body = Image.new("RGB", body_mask.size, BEZEL)
    canvas.paste(body, (x + e, y + e), body_mask)

    # Screen
    sw, sh_ = pw - 2 * b, ph - 2 * b
    screen = shot.convert("RGB")
    if screen.size != (sw, sh_):
        # cover-fit (aspect already matches to within rounding)
        scale = max(sw / screen.width, sh_ / screen.height)
        nw, nh = max(sw, round(screen.width * scale)), max(sh_, round(screen.height * scale))
        screen = screen.resize((nw, nh), Image.LANCZOS)
        left, top = (nw - sw) // 2, (nh - sh_) // 2
        screen = screen.crop((left, top, left + sw, top + sh_))
    canvas.paste(screen, (x + b, y + b), rounded_mask(sw, sh_, R - b))


def render_frame(size, shot, title_block, sub_block, text_area):
    W, H = size
    area_top, area_bottom, phone_top = text_area
    px, py, pw, ph, b = phone_geometry(W, phone_top, H - int(MARGIN_BOTTOM * W),
                                       shot.width, shot.height)

    canvas = vertical_gradient(W, H, BG_TOP, BG_BOTTOM, gamma=1.35)
    glow = radial_glow(W, H, W / 2, H * 0.98, W * 0.95, H * 0.42, 0.24)
    canvas = Image.composite(Image.new("RGB", (W, H), EMBER), canvas, glow)
    canvas = dither(canvas)

    draw = ImageDraw.Draw(canvas)
    block_h = title_block.height()
    if sub_block:
        block_h += int(GAP_TITLE_SUB * W) + sub_block.height()
    band_bottom = max(area_bottom, py - int(GAP_TEXT_PHONE * W))
    top = area_top + max(0, (band_bottom - area_top - block_h) // 2)
    title_block.draw(draw, W / 2, top, TITLE_INK)
    if sub_block:
        sub_top = top + title_block.height() + int(GAP_TITLE_SUB * W)
        sub_block.draw(draw, W / 2, sub_top, SUB_INK)

    draw_phone(canvas, shot, px, py, pw, ph, b)
    return canvas


# --------------------------------------------------------------------------
# Inputs
# --------------------------------------------------------------------------

def load_captions(path):
    if not os.path.isfile(path):
        raise FrameError("caption file missing: %s" % path)
    try:
        with open(path, encoding="utf-8") as fh:
            data = json.load(fh)
    except ValueError as e:
        raise FrameError("caption file %s is not valid JSON: %s" % (path, e))
    if not isinstance(data, dict):
        raise FrameError("caption file %s must hold a JSON object" % path)
    out = {}
    for key in SCREEN_IDS:
        if key not in data:
            raise FrameError("caption file %s has no key %r" % (path, key))
        v = data[key]
        if isinstance(v, str):
            title, sub = v, None
        elif isinstance(v, dict):
            title, sub = v.get("title"), v.get("sub")
            extra = set(v) - {"title", "sub"}
            if extra:
                warn("%s: %s has unknown fields %s (ignored)" % (path, key, sorted(extra)))
        else:
            raise FrameError("%s: %s must be a string or {title, sub}" % (path, key))
        if not isinstance(title, str) or not title.strip():
            raise FrameError("%s: %s has an empty title" % (path, key))
        if sub is not None and (not isinstance(sub, str) or not sub.strip()):
            raise FrameError("%s: %s has an empty or non-string sub" % (path, key))
        out[key] = (title.strip(), sub.strip() if sub else None)
    for key in data:
        if key not in SCREEN_IDS:
            warn("%s: unknown key %r (ignored)" % (path, key))
    return out


def check_input(path):
    if not os.path.isfile(path):
        raise FrameError("missing screenshot: %s" % path)
    n = os.path.getsize(path)
    if n < MIN_INPUT_BYTES:
        raise FrameError("screenshot %s is only %d bytes (< 40 KB); placeholder?" % (path, n))
    try:
        img = Image.open(path)
        img.load()
    except Exception as e:
        raise FrameError("cannot read %s: %s" % (path, e))
    w, h = img.size
    ratio = w / h
    if not any(abs(ratio - a) / a <= ASPECT_TOLERANCE for a in ASPECTS):
        raise FrameError("screenshot %s is %dx%d (aspect %.3f); expected portrait ~9:16 or ~19.5:9"
                         % (path, w, h, ratio))
    return img


def find_ios_shot(folder, nn_id):
    if not os.path.isdir(folder):
        raise FrameError("missing screenshot folder: %s" % folder)
    want = nn_id + ".png"
    hits = []
    for name in sorted(os.listdir(folder)):
        if name == want or (name.endswith(want) and name[-len(want) - 1] in "-_ "):
            hits.append(name)
    if not hits:
        raise FrameError("missing screenshot: %s/*%s" % (folder, want))
    if len(hits) > 1:
        pref = [h for h in hits if h.startswith("iPhone 17 Pro Max")]
        if len(pref) == 1:
            warn("%s: several matches for %s, using %r" % (folder, want, pref[0]))
            hits = pref
        else:
            raise FrameError("ambiguous screenshots for %s in %s: %s" % (want, folder, hits))
    return os.path.join(folder, hits[0])


def jobs_for(platform, repo, locale):
    app_lang, play, asc = locale
    jobs = []
    if platform == "android":
        src = os.path.join(repo, "fastlane", "metadata", "android", play, "images", "phoneScreenshots")
        dst = os.path.join(repo, "fastlane", "framed", "android", play)
        if not os.path.isdir(src):
            raise FrameError("missing screenshot folder: %s" % src)
        for sid in SCREEN_IDS:
            jobs.append((sid, os.path.join(src, sid + ".png"), os.path.join(dst, sid + ".png")))
        return play, dst, jobs
    src = os.path.join(repo, "fastlane", "screenshots", asc)
    dst = os.path.join(repo, "fastlane", "framed", "ios", asc)
    for i, sid in enumerate(SCREEN_IDS, 1):
        nn = "%02d_%s" % (i, sid.split("_", 1)[1])
        jobs.append((sid, find_ios_shot(src, nn), os.path.join(dst, nn + ".png")))
    return asc, dst, jobs


# --------------------------------------------------------------------------
# Per-locale run
# --------------------------------------------------------------------------

def frame_locale(platform, repo, locale, fonts):
    app_lang = locale[0]
    W, H = CANVAS[platform]
    captions = load_captions(os.path.join(repo, "fastlane", "captions", app_lang + ".json"))
    label, dst, jobs = jobs_for(platform, repo, locale)
    shots = {sid: check_input(src) for sid, src, _ in jobs}

    script = script_for(app_lang)
    bold_c, reg_c = fonts[script]
    all_titles = "".join(t for t, _ in captions.values())
    all_subs = "".join(s for _, s in captions.values() if s)
    title_spec = choose_font(bold_c, all_titles, "headline", script)
    sub_spec = choose_font(reg_c, all_subs, "subline", script) if all_subs else None

    max_w = W * (1 - 2 * MARGIN_X)
    # One headline size per locale so the set reads as a series.
    tsize = None
    for sid in SCREEN_IDS:
        s = fit_size(title_spec, captions[sid][0], max_w, TITLE_MAX * W, TITLE_MIN * W)
        if s is None:
            raise FrameError("%s: headline for %s does not fit in 2 lines even at %dpx: %r"
                             % (app_lang, sid, TITLE_MIN * W, captions[sid][0]))
        tsize = s if tsize is None else min(tsize, s)
    ssize = None
    if sub_spec:
        for sid in SCREEN_IDS:
            sub = captions[sid][1]
            if not sub:
                continue
            s = fit_size(sub_spec, sub, max_w, SUB_MAX * W, SUB_MIN * W)
            if s is None:
                raise FrameError("%s: subline for %s does not fit in 2 lines: %r" % (app_lang, sid, sub))
            ssize = s if ssize is None else min(ssize, s)

    blocks = {}
    for sid in SCREEN_IDS:
        title, sub = captions[sid]
        tb = make_block(title_spec, tsize, title, max_w, script, 1.13)
        sb = make_block(sub_spec, ssize, sub, max_w, script, 1.30) if sub else None
        blocks[sid] = (tb, sb)

    # Reserve the same text area on every frame of the locale (room for two
    # headline lines, plus a subline if any frame has one) so the phones line up.
    area_top = int(MARGIN_TOP * W)
    tb0 = blocks[SCREEN_IDS[0]][0]
    reserve = tb0.height(2)
    sub_lines = max((len(sb.lines) for _, sb in blocks.values() if sb), default=0)
    if sub_lines:
        sb0 = next(sb for _, sb in blocks.values() if sb)
        reserve += int(GAP_TITLE_SUB * W) + sb0.height(sub_lines)
    area_bottom = area_top + reserve
    phone_top = area_bottom + int(GAP_TEXT_PHONE * W)

    os.makedirs(dst, exist_ok=True)
    count = 0
    for sid, _src, out in jobs:
        tb, sb = blocks[sid]
        img = render_frame((W, H), shots[sid], tb, sb, (area_top, area_bottom, phone_top))
        assert img.size == (W, H) and img.mode == "RGB"
        img.save(out, "PNG")
        count += 1
    fontname = os.path.basename(title_spec.path)
    return label, dst, count, tsize, fontname


# --------------------------------------------------------------------------
# Contact sheet
# --------------------------------------------------------------------------

def contact_sheet(platform, repo, out_path, fonts):
    W, H = CANVAS[platform]
    thumb_w = 200 if platform == "android" else 168
    thumb_h = round(thumb_w * H / W)
    gap, label_w, pad = 14, 112, 28
    rows = []
    for app_lang, play, asc in LOCALES:
        loc = play if platform == "android" else asc
        folder = os.path.join(repo, "fastlane", "framed", platform, loc)
        files = []
        for i, sid in enumerate(SCREEN_IDS, 1):
            name = sid if platform == "android" else "%02d_%s" % (i, sid.split("_", 1)[1])
            p = os.path.join(folder, name + ".png")
            files.append(p if os.path.isfile(p) else None)
        if any(files):
            rows.append((loc, files))
    if not rows:
        raise FrameError("contact sheet: no framed outputs under fastlane/framed/%s" % platform)
    cols = len(SCREEN_IDS)
    sw = pad * 2 + label_w + cols * thumb_w + (cols - 1) * gap
    sh = pad * 2 + len(rows) * thumb_h + (len(rows) - 1) * gap
    sheet = Image.new("RGB", (sw, sh), (10, 9, 12))
    d = ImageDraw.Draw(sheet)
    spec = None
    for cand in fonts["latin"][1] + fonts["latin"][0]:
        try:
            if cand.resolve():
                spec = cand
                break
        except OSError:
            continue
    f = spec.font(22) if spec else ImageFont.load_default()
    for r, (loc, files) in enumerate(rows):
        y = pad + r * (thumb_h + gap)
        d.text((pad, y + 6), loc, font=f, fill=SUB_INK)
        for c, p in enumerate(files):
            x = pad + label_w + c * (thumb_w + gap)
            if p:
                im = Image.open(p).convert("RGB").resize((thumb_w, thumb_h), Image.LANCZOS)
                sheet.paste(im, (x, y))
            else:
                d.rectangle((x, y, x + thumb_w - 1, y + thumb_h - 1), outline=(120, 40, 40), width=2)
    parent = os.path.dirname(os.path.abspath(out_path))
    os.makedirs(parent, exist_ok=True)
    sheet.save(out_path, "PNG")
    return len(rows), sum(1 for _, fs in rows for p in fs if p)


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------

def match_locale(arg):
    a = arg.strip().lower().replace("_", "-")
    for loc in LOCALES:
        if a in (x.lower() for x in loc):
            return loc
    raise FrameError("--only %r matches no locale; known: %s"
                     % (arg, ", ".join("/".join(sorted(set(l))) for l in LOCALES)))


def main(argv=None):
    ap = argparse.ArgumentParser(description="Frame BurnPony screenshots for the stores.")
    ap.add_argument("--platform", required=True, choices=["android", "ios"])
    ap.add_argument("--repo", default=".", help="repo root (default: .)")
    ap.add_argument("--font-dir", help="use Noto fonts from this directory instead of macOS system fonts")
    ap.add_argument("--only", help="one locale (app code, Play or App Store locale)")
    ap.add_argument("--contact-sheet", metavar="OUT.png",
                    help="also tile every framed output of the platform into one review image")
    args = ap.parse_args(argv)

    repo = os.path.abspath(args.repo)
    try:
        fonts = dir_font_candidates(args.font_dir) if args.font_dir else mac_font_candidates()
        locales = [match_locale(args.only)] if args.only else LOCALES
    except FrameError as e:
        print("error: %s" % e, file=sys.stderr)
        return 2

    total, failures = 0, 0
    for loc in locales:
        name = loc[1] if args.platform == "android" else loc[2]
        try:
            label, dst, n, tsize, fontname = frame_locale(args.platform, repo, loc, fonts)
            total += n
            print("%-8s %d framed  headline %dpx %s  -> %s"
                  % (label, n, tsize, fontname, os.path.relpath(dst, repo)))
        except FrameError as e:
            failures += 1
            print("%-8s FAILED  %s" % (name, e), file=sys.stderr)

    if args.contact_sheet:
        try:
            rows, n = contact_sheet(args.platform, repo, args.contact_sheet, fonts)
            print("contact sheet: %d locales, %d frames -> %s" % (rows, n, args.contact_sheet))
        except FrameError as e:
            failures += 1
            print("contact sheet FAILED  %s" % e, file=sys.stderr)

    print("total: %d frames for %s, %d locale(s) failed" % (total, args.platform, failures))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
