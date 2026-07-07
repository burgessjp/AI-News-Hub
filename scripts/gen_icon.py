#!/usr/bin/env python3
"""
AIHot launcher icon generator.

Design: "AI Flame"
  - Background: diagonal gradient dark-teal (top-left) -> near-black (bottom-right)
                with a soft cyan glow in the upper-center.
  - Foreground: cyan-gradient flame (bright #67e8f9 -> deep teal #0e7490),
                an inner brighter core for glow,
                and a white 4-point sparkle (AI mark) in the upper-center.

Outputs (under app/src/main/res):
  - drawable-xxxhdpi/ic_launcher_background.png  (432px, layer)
  - drawable-xxxhdpi/ic_launcher_foreground.png  (432px, transparent layer)
  - mipmap-anydpi-v26/ic_launcher.xml
  - mipmap-anydpi-v26/ic_launcher_round.xml
  - mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png        (48/72/96/144/192)
  - mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_round.png  (circle-masked)

Usage:
  python3 scripts/gen_icon.py            # generate everything
  python3 scripts/gen_icon.py --preview  # write /tmp/icon_preview.png only
"""
import os
import sys
import math
import argparse

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

# ---------------------------------------------------------------- palette
CYAN_BRIGHT = (0x67, 0xE8, 0xF9)   # core / top of flame
CYAN_ACCENT = (0x22, 0xD3, 0xEE)   # brand accent
TEAL_MID    = (0x08, 0x91, 0xB2)
TEAL_DEEP   = (0x0E, 0x74, 0x90)
WHITE       = (0xFF, 0xFF, 0xFF)

BG_TOP_LEFT = (0x0A, 0x4F, 0x5A)   # dark teal
BG_BOTTOM_R = (0x06, 0x08, 0x0F)   # near-black (matches site dark bg)
GLOW        = (0x22, 0xD3, 0xEE)

# master render resolution (downscaled for crisp anti-aliasing)
MASTER = 1536
ADAPTIVE_DP = 432                   # adaptive icon layer export size
LEGACY = {                          # density -> px (48dp base)
    "mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192,
}

RES = os.path.join("app", "src", "main", "res")


# ---------------------------------------------------------------- helpers
def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def diagonal_gradient(size, c_tl, c_br):
    """Diagonal top-left -> bottom-right gradient as uint8 HxWx3."""
    t = np.zeros((size, size, 3), dtype=np.float32)
    # normalized diagonal coordinate
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    d = (xx + yy) / (2 * (size - 1))           # 0 tl .. 1 br
    for i in range(3):
        t[..., i] = c_tl[i] + (c_br[i] - c_tl[i]) * d
    return np.clip(t, 0, 255).astype(np.uint8)


def radial_glow(size, center, radius, color, intensity=1.0):
    """Return RGBA float array (HxWx4) with a soft radial glow."""
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    cx, cy = center
    dist = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) / radius
    a = np.clip(1.0 - dist, 0.0, 1.0) ** 2.2
    a *= intensity
    out = np.zeros((size, size, 4), dtype=np.float32)
    out[..., 0] = color[0]
    out[..., 1] = color[1]
    out[..., 2] = color[2]
    out[..., 3] = a * 255.0
    return out


def flame_outline(size, cx, tip_y, base_y, max_half):
    """
    Build a closed flame polygon (list of (x,y)) with a pointed tip at top,
    rounded base, and two asymmetric side licks for an organic flame.
    Coordinates in pixels.
    """
    n_side = 80
    right, left = [], []

    def profile(t):
        """
        Flame half-width profile, t: 0 at tip .. 1 at base.
        Classic candle-flame: sharp tip at top, widens to broad
        lower-middle belly (~t=0.62), gentle rounding at base.
        """
        # broad belly, centered lower-mid
        belly = math.exp(-(((t - 0.62) / 0.30) ** 2))
        # subtle waist pinch just below the tip (t~0.22) for flame neck
        neck = math.exp(-(((t - 0.22) / 0.10) ** 2))
        # gentle taper so base isn't wider than belly
        base_taper = 0.10 * max(0.0, (0.92 - t) / 0.92)
        w = max_half * (1.00 * belly - 0.18 * neck + base_taper)
        return max(w, max_half * 0.015)

    def bump(t, center, amp, width):
        return amp * math.exp(-(((t - center) / width) ** 2))

    # asymmetric lean: shift center axis slightly to the right toward the tip
    lean = max_half * 0.05
    for i in range(n_side + 1):
        t = i / n_side
        y = tip_y + t * (base_y - tip_y)
        w = profile(t)
        # right side: a flame lick near the upper neck/belly
        w_r = w + bump(t, 0.30, max_half * 0.16, 0.08)
        # left side: a lower-side outward curl
        w_l = w + bump(t, 0.78, max_half * 0.10, 0.10)
        ax = cx + lean * (1 - t)   # axis leans toward upper-right
        right.append((ax + w_r, y))
        left.append((ax - w_l, y))

    # rounded base: arc from right-base to left-base (slightly below)
    base_cx, base_cy = cx, base_y
    base_rx = max_half * 0.78
    base_ry = max_half * 0.42
    arc = []
    steps = 24
    for i in range(steps + 1):
        a = math.pi * (i / steps)         # 0 (right) -> pi (left)
        x = base_cx + base_rx * math.cos(a)
        y = base_cy + base_ry * math.sin(a) * 0.9
        arc.append((x, y))

    # Insert an explicit sharp tip point at the very top so the flame
    # comes to a point rather than a flat top from sampling.
    tip_point = (cx + lean, tip_y - max_half * 0.05)
    polygon = [tip_point] + right + arc + list(reversed(left))
    # close back to tip
    polygon.append(tip_point)
    return polygon


def vertical_gradient(size, bbox, c_top, c_bottom):
    """Vertical gradient image cropped to whole canvas; returns RGBA uint8."""
    x0, y0, x1, y1 = bbox
    h = max(1, int(y1 - y0))
    grad = np.zeros((size, size, 4), dtype=np.float32)
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    t = np.clip((yy - y0) / max(1, (y1 - y0)), 0, 1)
    for i in range(3):
        grad[..., i] = c_top[i] + (c_bottom[i] - c_top[i]) * t
    grad[..., 3] = 0
    return grad


def mask_from_polygon(size, polygon, feather=0.0):
    img = Image.new("L", (size, size), 0)
    ImageDraw.Draw(img).polygon([(float(x), float(y)) for x, y in polygon], fill=255)
    if feather:
        img = img.filter(ImageFilter.GaussianBlur(feather))
    return np.asarray(img).astype(np.float32) / 255.0


def sparkle_polygon(size, cx, cy, r_out, r_in, rot=0.0):
    """4-point concave star (✦) polygon."""
    pts = []
    for k in range(8):
        ang = math.pi / 2 + k * (math.pi / 4) + rot
        rr = r_out if k % 2 == 0 else r_in
        pts.append((cx + rr * math.cos(ang), cy - rr * math.sin(ang)))
    return pts


# ---------------------------------------------------------------- layers
def build_background(size):
    arr = diagonal_gradient(size, BG_TOP_LEFT, BG_BOTTOM_R).astype(np.float32)
    # add cyan glow upper-center
    glow = radial_glow(size, (size * 0.5, size * 0.42), size * 0.55, GLOW, intensity=0.55)
    rgba = np.zeros((size, size, 4), dtype=np.float32)
    rgba[..., :3] = arr
    rgba[..., 3] = 255
    # screen-blend glow
    a = (glow[..., 3:4] / 255.0)
    rgba[..., :3] = rgba[..., :3] * (1 - a * 0.8) + glow[..., :3] * (a * 0.8)
    rgba = np.clip(rgba, 0, 255).astype(np.uint8)
    return Image.fromarray(rgba, "RGBA")


def build_foreground(size):
    """Transparent foreground: flame (gradient + core) + sparkle."""
    canvas = np.zeros((size, size, 4), dtype=np.float32)

    # ---- flame ----
    # keep within adaptive safe zone (~66% center): margin ~17% each side
    cx = size * 0.50
    tip_y = size * 0.13
    base_y = size * 0.85
    max_half = size * 0.28
    flame = flame_outline(size, cx, tip_y, base_y, max_half)

    bbox = (cx - max_half * 1.3, tip_y, cx + max_half * 1.3, base_y)
    grad = vertical_gradient(size, bbox, CYAN_BRIGHT, TEAL_DEEP)
    flame_mask = mask_from_polygon(size, flame, feather=size * 0.004)

    # composite gradient into canvas via flame_mask
    for i in range(3):
        canvas[..., i] += grad[..., i] * flame_mask
    canvas[..., 3] = np.maximum(canvas[..., 3], flame_mask * 255)

    # inner brighter core (smaller, slightly up)
    core = flame_outline(size, cx, tip_y + size * 0.06, base_y - size * 0.10, max_half * 0.55)
    core_mask = mask_from_polygon(size, core, feather=size * 0.010)
    core_grad = vertical_gradient(size, bbox, WHITE, CYAN_ACCENT)
    add = core_mask * 0.85
    for i in range(3):
        canvas[..., i] = canvas[..., i] * (1 - add) + core_grad[..., i] * add
    # boost alpha a touch where core is
    canvas[..., 3] = np.maximum(canvas[..., 3], (flame_mask * 255) )

    # ---- sparkle (AI mark) in upper-center of flame ----
    sp_cx = cx
    sp_cy = size * 0.44
    sp = sparkle_polygon(size, sp_cx, sp_cy, size * 0.095, size * 0.026)
    sp_mask = mask_from_polygon(size, sp, feather=size * 0.0025)
    # soft glow behind sparkle
    sp_glow = mask_from_polygon(size, sp, feather=size * 0.022)
    for i in range(3):
        canvas[..., i] = canvas[..., i] * (1 - sp_glow * 0.6) + np.full((size, size), WHITE[i], dtype=np.float32) * (sp_glow * 0.6)
    canvas[..., 3] = np.maximum(canvas[..., 3], sp_glow * 255)
    # crisp white sparkle on top
    for i in range(3):
        canvas[..., i] = canvas[..., i] * (1 - sp_mask) + 255.0 * sp_mask
    canvas[..., 3] = np.maximum(canvas[..., 3], sp_mask * 255)

    canvas = np.clip(canvas, 0, 255).astype(np.uint8)
    return Image.fromarray(canvas, "RGBA")


def downscale(img, size):
    return img.resize((size, size), Image.LANCZOS)


def circle_mask(size):
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    cx = cy = (size - 1) / 2.0
    r = size / 2.0
    a = np.clip(r - np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2), 0, 1)
    return (a * 255).astype(np.uint8)


# ---------------------------------------------------------------- main
def write_adaptive_xml(path, fg, bg):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            "<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
            f"    <background android:drawable=\"{bg}\" />\n"
            f"    <foreground android:drawable=\"{fg}\" />\n"
            "</adaptive-icon>\n"
        )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", action="store_true",
                    help="only write /tmp/icon_preview.png and exit")
    args = ap.parse_args()

    bg = build_background(MASTER)
    fg = build_foreground(MASTER)

    composite = Image.alpha_composite(
        bg.convert("RGBA"), fg.convert("RGBA")
    )

    if args.preview:
        out = "/tmp/icon_preview.png"
        composite.resize((512, 512), Image.LANCZOS).save(out)
        # also save a round preview
        rnd = composite.resize((512, 512), Image.LANCZOS)
        arr = np.asarray(rnd).copy()
        arr[..., 3] = np.minimum(arr[..., 3], circle_mask(512))
        Image.fromarray(arr, "RGBA").save("/tmp/icon_preview_round.png")
        # foreground only
        fg.resize((512, 512), Image.LANCZOS).save("/tmp/icon_preview_fg.png")
        bg.resize((512, 512), Image.LANCZOS).save("/tmp/icon_preview_bg.png")
        print("preview -> /tmp/icon_preview*.png")
        return

    # adaptive layers
    dxx = os.path.join(RES, "drawable-xxxhdpi")
    os.makedirs(dxx, exist_ok=True)
    downscale(bg, ADAPTIVE_DP).save(os.path.join(dxx, "ic_launcher_background.png"))
    downscale(fg, ADAPTIVE_DP).save(os.path.join(dxx, "ic_launcher_foreground.png"))

    # adaptive xml
    v26 = os.path.join(RES, "mipmap-anydpi-v26")
    write_adaptive_xml(
        os.path.join(v26, "ic_launcher.xml"),
        fg="@drawable/ic_launcher_foreground",
        bg="@drawable/ic_launcher_background",
    )
    write_adaptive_xml(
        os.path.join(v26, "ic_launcher_round.xml"),
        fg="@drawable/ic_launcher_foreground",
        bg="@drawable/ic_launcher_background",
    )

    # legacy PNGs (composite) for each density
    for density, px in LEGACY.items():
        d = os.path.join(RES, f"mipmap-{density}")
        os.makedirs(d, exist_ok=True)
        sq = downscale(composite, px)
        sq.save(os.path.join(d, "ic_launcher.png"))
        # round variant: apply circle alpha mask
        arr = np.asarray(sq).copy()
        arr[..., 3] = (np.asarray(sq)[..., 3].astype(np.float32) / 255.0
                       * circle_mask(px).astype(np.float32) / 255.0 * 255).astype(np.uint8)
        Image.fromarray(arr, "RGBA").save(os.path.join(d, "ic_launcher_round.png"))

    print("done.")


if __name__ == "__main__":
    main()
