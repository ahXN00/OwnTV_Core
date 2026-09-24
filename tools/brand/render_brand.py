"""Renders the OwnTV flip-card brand images from the pass-6 mockup's own numbers.

The source of truth is future_work/OwnTV_Icon_Pass6_FlipCard.html. Every coordinate, colour and effect
below is copied from its PAL object and geometry constants (TOP, BOT, PLAY, LIP, TABS, TABS_S), in the
same 108-unit adaptive-icon canvas. Change the mockup first, then this file, never the other way round.

    python tools/brand/render_brand.py extras  --font <Roboto.ttf> --out <repo>/extras
    python tools/brand/render_brand.py android --font <Roboto.ttf> --out core/src/main/res

Needs Pillow. The wordmark uses the apps' default font (the system sans, Roboto on Android).
"""
import argparse
import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

# ---- palettes: hex for hex from the mockup's PAL -------------------------------------------------
PAL = {
    'petrol':    dict(top='#2F8F8A', bot='#227773', deep='#155653', gap='#0B3B39', pt='#FFFFFF', pb='#DDEDEC', field='#062321', acc='#5CC2BC', ui='#1B6E6A'),
    'sunflower': dict(top='#FFD24D', bot='#F4B82A', deep='#B7820F', gap='#7A5406', pt='#2B2208', pb='#3A2F0E', field='#231A04', acc='#FFD24D', ui='#946500'),
    'cobalt':    dict(top='#7299F8', bot='#4E77E8', deep='#2F50BD', gap='#1C3386', pt='#FFFFFF', pb='#E6ECFB', field='#0C1538', acc='#8AAAFF', ui='#3558C8'),
    'tomato':    dict(top='#FF8C72', bot='#F1644C', deep='#BF412D', gap='#822819', pt='#FFFFFF', pb='#FBE9E4', field='#2C0D07', acc='#FF9C84', ui='#C4412C'),
    'board':     dict(top='#3B424C', bot='#2D333B', deep='#1B1F25', gap='#0E1013', pt='#52DBC8', pb='#3FC4B1', field='#0D1012', acc='#52DBC8', ui='#13806F'),
    'eggshell':  dict(top='#F5EDDA', bot='#EADDC0', deep='#CBB795', gap='#4A3D2A', pt='#17616C', pb='#12545E', field='#294E50', acc='#86D3DB', ui='#17616C', dot='#E24B36'),
    'olive':     dict(top='#8DB636', bot='#779E23', deep='#56741A', gap='#3A4F10', pt='#FFFFFF', pb='#EEF3E2', field='#1E2A09', acc='#A5CC4E', ui='#5E7F1B'),
    'olive_cream': dict(top='#F4F1E4', bot='#E8E3CE', deep='#C9C09F', gap='#3E4A1C', pt='#779E23', pb='#66881D', field='#26330C', acc='#A5CC4E', ui='#5E7F1B', dot='#E0A526'),
}

# ---- geometry: the mockup's constants ------------------------------------------------------------
CARD = (32, 29, 76, 75, 10)            # TOP + BOT together: x0, y0, x1, y1, corner radius
HINGE = 52
PLAY = [(48.7, 42), (66.2, 52), (48.7, 62)]
LIP = (37, 68, 71, 78, 4)              # rect x37 y68 w34 h10 rx4
TABS = [(28.5, 47.5, 35.5, 56.5, 2.3), (72.5, 47.5, 79.5, 56.5, 2.3)]
TABS_S = [(27.3, 46.5, 35.3, 57.5, 2.6), (72.7, 46.5, 80.7, 57.5, 2.6)]
DOT = (68.5, 36, 1.9)
VB_FULL = (18, 72)                      # viewBox="18 18 72 72": the launcher canvas
VB_TIGHT = (26, 56)                     # viewBox="26 26 56 56": in-app, cropped to the card
SHADOW = dict(dy=2.2, blur=2.0, alpha=0.30)   # feDropShadow dx0 dy2.2 stdDeviation2 black .3
SS = 4                                  # supersampling for clean edges


def rgb(h, a=255):
    h = h.lstrip('#')
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


class Canvas:
    """Maps 108-unit mockup coordinates onto a supersampled square image."""

    def __init__(self, px, vb):
        self.w = px * SS
        self.o, self.size = vb
        self.k = self.w / self.size
        self.dy = 0.0

    def x(self, v): return (v - self.o) * self.k
    def y(self, v): return (v - self.o + self.dy) * self.k
    def box(self, x0, y0, x1, y1): return (self.x(x0), self.y(y0), self.x(x1), self.y(y1))
    def layer(self): return Image.new('RGBA', (self.w, self.w), (0, 0, 0, 0))
    def mask(self): return Image.new('L', (self.w, self.w), 0)

    def rrect(self, img, r, fill):
        x0, y0, x1, y1, rad = r
        ImageDraw.Draw(img).rounded_rectangle(self.box(x0, y0, x1, y1), radius=rad * self.k, fill=fill)

    def play_mask(self, sw):
        """The play: filled triangle stroked with a round join, i.e. grown by sw/2 on every side."""
        m = self.mask()
        d = ImageDraw.Draw(m)
        pts = [(self.x(a), self.y(b)) for a, b in PLAY]
        d.polygon(pts, fill=255)
        r = sw / 2 * self.k
        for i in range(3):
            d.line([pts[i], pts[(i + 1) % 3]], fill=255, width=int(round(sw * self.k)))
        for px, py in pts:
            d.ellipse((px - r, py - r, px + r, py + r), fill=255)
        return m

    def split(self, m, top):
        """Keep the part of a mask above (top=True) or below the hinge."""
        out = m.copy()
        cut = int(round(self.y(HINGE)))
        ImageDraw.Draw(out).rectangle((0, cut, self.w, self.w) if top else (0, 0, self.w, cut - 1), fill=0)
        return out


def mark(p, px, variant='launcher', vb=VB_FULL, lip=True, shadow=True):
    """variant: 'launcher' (next card + shadow), 'flat' (in-app: no shadow, no next card), 'small' (<=32 px)."""
    small = variant == 'small'
    use_lip = lip and variant == 'launcher'
    use_shadow = shadow and variant == 'launcher'
    c = Canvas(px, vb)
    c.dy = 0.5 if use_lip else 2
    sw = 6 if small else 4
    # No hinge line and no shade band on any still image (owner, 2026-09-24): they appear only in the
    # flip animation, where the flap needs them. The two flat halves still read as the split card.
    obj = c.layer()
    if use_lip:
        c.rrect(obj, LIP, rgb(p['deep']))
    for t in (TABS_S if small else TABS):
        c.rrect(obj, t, rgb(p['deep']))
    card = c.mask()
    c.rrect(card, CARD, 255)
    obj.paste(rgb(p['top']), (0, 0), c.split(card, True))
    obj.paste(rgb(p['bot']), (0, 0), c.split(card, False))
    pm = c.play_mask(sw)
    obj.paste(rgb(p['pt']), (0, 0), c.split(pm, True))
    obj.paste(rgb(p['pb']), (0, 0), c.split(pm, False))
    if p.get('dot') and not small:
        cx, cy, r = DOT
        ImageDraw.Draw(obj).ellipse(c.box(cx - r, cy - r, cx + r, cy + r), fill=rgb(p['dot']))
    out = obj
    if use_shadow:
        a = obj.getchannel('A').filter(ImageFilter.GaussianBlur(SHADOW['blur'] * c.k))
        a = a.point(lambda v: int(v * SHADOW['alpha']))
        sh = c.layer()
        sh.paste((0, 0, 0, 255), (0, int(round(SHADOW['dy'] * c.k))), a)
        out = Image.alpha_composite(sh, obj)
    return out.resize((px, px), Image.LANCZOS)


def font(path, size):
    f = ImageFont.truetype(path, size)
    try:
        f.set_variation_by_axes([800, 100])   # mockup wordmark: weight 800
    except Exception:
        pass
    return f


def wordmark(draw, xy, text_parts, f, tracking):
    """Draws "Own" + "TV" letter by letter with the mockup's -0.025em tracking. Returns the end x."""
    x, y = xy
    for text, colour in text_parts:
        for ch in text:
            draw.text((x, y), ch, font=f, fill=colour, anchor='ls')
            x += f.getlength(ch) + tracking
    return x


def word_width(text, f, tracking):
    return sum(f.getlength(ch) + tracking for ch in text) - tracking


def lockup(p, mark_px, font_px, font_path, own='#FFFFFF', pad=0, tv=None):
    """Horizontal lockup (mockup lockH): flat mark, gap = mark * .26, wordmark. On light: own=ink, tv=p['ui']."""
    f = font(font_path, font_px)
    tr = -0.025 * font_px
    ww = word_width('OwnTV', f, tr)
    gap = round(mark_px * .26)
    w = pad * 2 + mark_px + gap + int(ww) + 4
    h = pad * 2 + max(mark_px, int(font_px * 1.2))
    img = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    img.alpha_composite(mark(p, mark_px, 'flat' if mark_px > 32 else 'small', VB_TIGHT), (pad, (h - mark_px) // 2))
    d = ImageDraw.Draw(img)
    asc = f.getbbox('OwnTV', anchor='ls')           # centre the letters' ink on the mark
    base = h / 2 - (asc[1] + asc[3]) / 2
    wordmark(d, (pad + mark_px + gap, base), [('Own', own), ('TV', tv or p['acc'])], f, tr)
    return img


def banner(p, w, h, font_path):
    """The TV banner layout from the mockup, scaled from its 320x180 artboard."""
    s = h / 180
    img = Image.new('RGBA', (w, h), rgb(p['field']))
    mk = round(108 * s)
    content_w = (150 - 26) * s + word_width('OwnTV', font(font_path, round(48 * s)), -1.2 * s)
    x0 = round((w - content_w) / 2) if w / h > 320 / 180 + .01 else round(26 * s)   # wide banners centre the lockup
    img.alpha_composite(mark(p, mk, 'flat', VB_TIGHT), (x0, round(36 * s)))
    wordmark(ImageDraw.Draw(img), (x0 + (150 - 26) * s, 106 * s), [('Own', '#FFFFFF'), ('TV', p['acc'])],
             font(font_path, round(48 * s)), -1.2 * s)
    return img


# ---- Android vector drawables ---------------------------------------------------------------------
# The same shapes as mark(), written as VectorDrawable path data. Flat versions only: a vector cannot
# blur, so the one shadow lives in the launcher foreground bitmaps above.

def f(v):
    return ('%.3f' % v).rstrip('0').rstrip('.')


def rr_path(x0, y0, x1, y1, r):
    return (f'M{f(x0 + r)},{f(y0)}H{f(x1 - r)}A{f(r)},{f(r)} 0 0 1 {f(x1)},{f(y0 + r)}V{f(y1 - r)}'
            f'A{f(r)},{f(r)} 0 0 1 {f(x1 - r)},{f(y1)}H{f(x0 + r)}A{f(r)},{f(r)} 0 0 1 {f(x0)},{f(y1 - r)}'
            f'V{f(y0 + r)}A{f(r)},{f(r)} 0 0 1 {f(x0 + r)},{f(y0)}Z')


def rect_path(x0, y0, x1, y1):
    return f'M{f(x0)},{f(y0)}H{f(x1)}V{f(y1)}H{f(x0)}Z'


def circle_path(cx, cy, r):
    return f'M{f(cx - r)},{f(cy)}a{f(r)},{f(r)} 0 1 0 {f(2 * r)},0a{f(r)},{f(r)} 0 1 0 {f(-2 * r)},0Z'


TOP_PATH = 'M32,52V39a10,10 0 0 1 10,-10h24a10,10 0 0 1 10,10v13z'      # the mockup's TOP
BOT_PATH = 'M32,52h44v13a10,10 0 0 1 -10,10H42a10,10 0 0 1 -10,-10z'    # the mockup's BOT
PLAY_PATH = 'M48.7,42L66.2,52L48.7,62Z'                                  # the mockup's PLAY
CLIP_TOP = 'M0,0H108V52H0Z'
CLIP_BOT = 'M0,52H108V108H0Z'


def _vec(body, vb, size_dp):
    o, s = vb
    return (f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{size_dp}dp" android:height="{size_dp}dp"\n'
            f'    android:viewportWidth="{s}" android:viewportHeight="{s}">\n'
            f'    <group android:translateX="{-o}" android:translateY="{-o}">\n{body}    </group>\n</vector>\n')


def _p(d, fill, alpha=None, name=None, stroke=None, sw=None, ind=8):
    a = [f'android:pathData="{d}"', f'android:fillColor="{fill}"']
    if name: a.insert(0, f'android:name="{name}"')
    if alpha is not None: a.append(f'android:fillAlpha="{alpha}"')
    if stroke: a += [f'android:strokeColor="{stroke}"', f'android:strokeWidth="{sw}"', 'android:strokeLineJoin="round"']
    return ' ' * ind + '<path ' + ' '.join(a) + '/>\n'


def _clipped(clip, inner, ind=8):
    sp = ' ' * ind
    return f'{sp}<group>\n{sp}    <clip-path android:pathData="{clip}"/>\n{inner}{sp}</group>\n'


def flat_body(p, small=False, ind=12):
    """Flat card (no next card, no shadow) in the mockup's 108 space, already shifted by dy = 2."""
    sw = 6 if small else 4
    b = ''.join(_p(rr_path(*t), p['deep'], ind=ind + 4) for t in (TABS_S if small else TABS))
    b += _p(TOP_PATH, p['top'], ind=ind + 4) + _p(BOT_PATH, p['bot'], ind=ind + 4)
    b += _clipped(CLIP_TOP, _p(PLAY_PATH, p['pt'], stroke=p['pt'], sw=sw, ind=ind + 8), ind + 4)
    b += _clipped(CLIP_BOT, _p(PLAY_PATH, p['pb'], stroke=p['pb'], sw=sw, ind=ind + 8), ind + 4)
    if p.get('dot') and not small:
        b += _p(circle_path(*DOT), p['dot'], ind=ind + 4)
    return f'{" " * ind}<group android:translateY="2">\n{b}{" " * ind}</group>\n'


def vector_mark(p, small=False, vb=VB_TIGHT, size_dp=56):
    return _vec(flat_body(p, small), vb, size_dp)


# -- one-colour silhouette: card + tabs solid, play cut out ------------------------------------------

def _rounded_triangle(r, n=16):
    """The play grown by r with round joins (the stroked PLAY), as a polygon."""
    import math
    pts = PLAY
    out = []
    for i in range(3):
        px, py = pts[i]
        ax, ay = pts[i - 1]
        bx, by = pts[(i + 1) % 3]

        def normal(x0, y0, x1, y1):   # outward normal of a clockwise-on-screen edge
            dx, dy = x1 - x0, y1 - y0
            ln = math.hypot(dx, dy)
            return (dy / ln, -dx / ln)
        n1 = normal(ax, ay, px, py)
        n2 = normal(px, py, bx, by)
        a1, a2 = math.atan2(n1[1], n1[0]), math.atan2(n2[1], n2[0])
        while a2 < a1: a2 += 2 * math.pi
        for k in range(n + 1):
            t = a1 + (a2 - a1) * k / n
            out.append((px + r * math.cos(t), py + r * math.sin(t)))
    return out


def _poly_path(poly, reverse=False):
    pts = list(reversed(poly)) if reverse else poly
    return 'M' + 'L'.join(f'{f(x)},{f(y)}' for x, y in pts) + 'Z'


def mono_path(small=False):
    sw = 6 if small else 4.4
    card = rr_path(32, 29, 76, 75, 10)
    # the card and tab subpaths run clockwise on screen; the play hole is reversed, so nonZero cuts it
    hole = _poly_path(_rounded_triangle(sw / 2), reverse=True)
    tabs = ''.join(rr_path(*t) for t in (TABS_S if small else TABS))
    return card + hole + tabs


def vector_mono(small=False, vb=VB_TIGHT, size_dp=24, colour='#FFFFFFFF'):
    body = f'        <group android:translateY="2">\n' + _p(mono_path(small), colour, ind=12) + '        </group>\n'
    return _vec(body, vb, size_dp)


# -- the launch animation: the card flips on ---------------------------------------------------------

def vector_splash_anim(p):
    """AnimatedVectorDrawable in the 108 canvas. Timeline = the mockup's, squeezed to 800 ms:
    pop-in 0-220 ms (scale .55 -> 1.04, fading in) then 1.04 -> 1 by 320 ms; the blank top flap falls to
    the hinge 380-560 ms; the new bottom flap drops in 560-700 ms to 1.07, settling to 1 by 800 ms."""
    g = 1.7
    ind = 16
    names = []

    def P(d, fill, alpha=None, stroke=None, sw=None, i=ind):
        n = f'p{len(names)}'
        names.append((n, stroke is not None, alpha or 1))
        return _p(d, fill, alpha, n, stroke, sw, i)
    b = ''.join(P(rr_path(*t), p['deep']) for t in TABS)
    b += P(TOP_PATH, p['top'])
    b += _clipped(CLIP_TOP, P(PLAY_PATH, p['pt'], stroke=p['pt'], sw=4, i=ind + 4), ind)
    if p.get('dot'):
        b += P(circle_path(*DOT), p['dot'])
    b += f'{" " * ind}<group android:name="fa" android:pivotY="52">\n' + P(TOP_PATH, p['top'], i=ind + 4) + f'{" " * ind}</group>\n'
    b += P(BOT_PATH, p['bot'])
    fb = P(BOT_PATH, p['bot'], i=ind + 4) + P(rect_path(32, HINGE + g / 2, 76, HINGE + g / 2 + 2.2), p['deep'], alpha='0.22', i=ind + 4)
    fb += _clipped(CLIP_BOT, P(PLAY_PATH, p['pb'], stroke=p['pb'], sw=4, i=ind + 8), ind + 4)
    b += f'{" " * ind}<group android:name="fb" android:pivotY="52" android:scaleY="0">\n{fb}{" " * ind}</group>\n'
    b += P(rect_path(32, HINGE - g / 2, 76, HINGE + g / 2), p['gap'])
    drawable = (f'<vector android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n'
                f'        <group android:name="ob" android:pivotX="54" android:pivotY="54" android:scaleX="0.55" android:scaleY="0.55">\n'
                f'            <group android:translateY="2">\n{b}            </group>\n        </group>\n    </vector>')

    def anim(prop, frm, to, dur, off=0, interp='@android:interpolator/linear'):
        return (f'<objectAnimator android:propertyName="{prop}" android:valueFrom="{frm}" android:valueTo="{to}" '
                f'android:valueType="floatType" android:duration="{dur}" android:startOffset="{off}" android:interpolator="{interp}"/>')

    def target(name, anims):
        return (f'    <target android:name="{name}">\n        <aapt:attr name="android:animation">\n'
                f'            <set>{"".join(anims)}</set>\n        </aapt:attr>\n    </target>\n')
    ease_out = '@android:interpolator/decelerate_quad'
    t = target('ob', [anim('scaleX', .55, 1.04, 220, 0, ease_out), anim('scaleY', .55, 1.04, 220, 0, ease_out),
                      anim('scaleX', 1.04, 1, 100, 220), anim('scaleY', 1.04, 1, 100, 220)])
    t += target('fa', [anim('scaleY', 1, 0, 180, 380, '@android:interpolator/accelerate_quad')])
    t += target('fb', [anim('scaleY', 0, 1.07, 140, 560, ease_out), anim('scaleY', 1.07, 1, 100, 700)])
    for n, stroked, full in names:
        a = [anim('fillAlpha', 0, full, 220)] + ([anim('strokeAlpha', 0, 1, 220)] if stroked else [])
        t += target(n, a)
    return ('<animated-vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    xmlns:aapt="http://schemas.android.com/aapt">\n'
            f'    <aapt:attr name="android:drawable">\n    {drawable}\n    </aapt:attr>\n{t}</animated-vector>\n')


DENSITIES = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}
VB_CANVAS = (0, 108)
HEADER = '<?xml version="1.0" encoding="utf-8"?>\n<!-- Generated by tools/brand/render_brand.py from the pass-6 mockup. Do not edit by hand. -->\n'


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as fh:
        fh.write(HEADER + text)


def icon_bg(p):
    a, b = rgb(p['bot']), rgb(p['deep'])
    return '#%02X%02X%02X' % tuple((x + y) // 2 for x, y in zip(a[:3], b[:3]))


def android(res, font_path):
    for key, p in PAL.items():
        for dens, k in DENSITIES.items():          # launcher foreground: the whole object, with its shadow
            d = os.path.join(res, f'mipmap-{dens}')
            os.makedirs(d, exist_ok=True)
            mark(p, round(108 * k), 'launcher', VB_CANVAS).save(os.path.join(d, f'owntv_icon_{key}_foreground.png'))
        write(os.path.join(res, 'mipmap-anydpi-v26', f'owntv_icon_{key}.xml'),
              '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
              f'    <background android:drawable="@color/owntv_icon_bg_{key}"/>\n'
              f'    <foreground android:drawable="@mipmap/owntv_icon_{key}_foreground"/>\n'
              '    <monochrome android:drawable="@drawable/owntv_icon_monochrome"/>\n</adaptive-icon>\n')
        write(os.path.join(res, 'drawable', f'owntv_mark_{key}.xml'), vector_mark(p))
        write(os.path.join(res, 'drawable', f'owntv_mark_small_{key}.xml'), vector_mark(p, small=True, size_dp=32))
        write(os.path.join(res, 'drawable', f'owntv_splash_static_{key}.xml'), vector_mark(p, vb=VB_CANVAS, size_dp=108))
        write(os.path.join(res, 'drawable', f'owntv_splash_{key}.xml'), vector_splash_anim(p))
        d = os.path.join(res, 'drawable-xhdpi')
        os.makedirs(d, exist_ok=True)
        banner(p, 320, 180, font_path).convert('RGB').save(os.path.join(d, f'owntv_banner_{key}.png'))
        for dens, k in DENSITIES.items():          # launch-screen name, Android 12+: 200x80 dp at the bottom
            d = os.path.join(res, f'drawable-{dens}')
            os.makedirs(d, exist_ok=True)
            splash_name(p, round(200 * k), round(80 * k), font_path).save(os.path.join(d, f'owntv_splash_name_{key}.png'))
    # Launchers never show an adaptive icon's background as transparent (OnePlus and Google TV paint it
    # black), so it is filled with the icon's own colour: halfway between the card's lower half and its
    # tabs, so the card reads lighter than it and the tabs darker, like VLC's cone on its orange circle.
    write(os.path.join(res, 'values', 'owntv_icon_colors.xml'),
          '<resources>\n' + ''.join(f'    <color name="owntv_icon_bg_{k}">{icon_bg(p)}</color>\n' for k, p in PAL.items())
          + '</resources>\n')
    write(os.path.join(res, 'drawable', 'owntv_icon_monochrome.xml'), vector_mono(vb=VB_CANVAS, size_dp=108))
    write(os.path.join(res, 'drawable', 'owntv_notification.xml'), vector_mono(small=True))


def splash_name(p, w, h, font_path):
    """The mockup's launch-screen wordmark, "Own" white and "TV" in the accent, centred on a transparent
    200x80 dp canvas: Android 12+ draws it at the bottom of the launch screen (windowSplashScreenBrandingImage)."""
    fpx = round(h * .45)
    f = font(font_path, fpx)
    tr = -0.025 * fpx
    img = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    b = f.getbbox('OwnTV', anchor='ls')
    wordmark(ImageDraw.Draw(img), ((w - word_width('OwnTV', f, tr)) / 2, h / 2 - (b[1] + b[3]) / 2),
             [('Own', '#FFFFFF'), ('TV', p['acc'])], f, tr)
    return img


def extras(root, font_path):
    """Every colour's README / release images into <repo>/extras/brand/."""
    for key, p in PAL.items():
        d = os.path.join(root, 'brand', 'app-logos')
        os.makedirs(d, exist_ok=True)
        # shown at width 360 in the READMEs, drawn at 2x
        lockup(p, 150, 108, font_path, pad=10).save(os.path.join(d, f'logo_{key}.png'))
        # the mockup's "on light" version (family card F): ink "Own", darker accent "TV"
        lockup(p, 150, 108, font_path, own='#16181D', pad=10, tv=p['ui']).save(os.path.join(d, f'logo_{key}_light.png'))
        d = os.path.join(root, 'brand', 'app-icons')
        os.makedirs(d, exist_ok=True)
        mark(p, 1024).save(os.path.join(d, f'icon_{key}.png'))
        d = os.path.join(root, 'brand', 'tv-banners')
        os.makedirs(d, exist_ok=True)
        banner(p, 1920, 384, font_path).convert('RGB').save(os.path.join(d, f'banner_{key}.png'))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('what', choices=['extras', 'android'])
    ap.add_argument('--font', required=True)
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    if a.what == 'android':
        android(a.out, a.font)
        return
    extras(a.out, a.font)

if __name__ == '__main__':
    main()
