#!/usr/bin/env python3
"""
AI News Hub launcher icon generator (v1.4.0 纸墨日报).

Design: "头版" (front page on a red seal)
  - Background: solid newspaper red (#B93B1D, LightPrimary of the paper-ink palette).
  - Foreground: a paper front page (#FBFAF7) carrying the news identity ——
    ink inscriptional serif "AI" masthead + ink double rule (报头语言,同 App 内
    DoubleRule), a red headline bar (头条) and three ink text bars (新闻行).
    表意:报纸头版形态传达 NEWS,AI 在报头位。

Debug variant (--debug): same front page on an ink (#1B1A17) seal ——
红 = 正式包 / 墨 = debug 包,纸墨双色系内一眼可分(配合 label 后缀 "(Debug)")。
Writes into app/src/debug/res (source-set overlay; pre-API-26 legacy mipmaps are
intentionally not overridden —— 老设备上 debug 与正式同图标,靠标签区分)。

  Layout: masthead/headline/bars all sit inside the adaptive safe zone
  (center 66% circle, never masked); the page card intentionally reaches beyond
  it so circular launcher masks clip its corners into an arc (standard practice
  for document-style icons).

  Typography is drawn as polygons (碑刻体: triangle-minus-triangle "A" with crossbar
  and foot slabs, stemmed "I" with serif slabs) — no font files needed, deterministic
  everywhere the script runs.

Outputs (under app/src/main/res):
  - drawable-xxxhdpi/ic_launcher_background.png  (432px, layer)
  - drawable-xxxhdpi/ic_launcher_foreground.png  (432px, transparent layer)
  - mipmap-anydpi-v26/ic_launcher.xml
  - mipmap-anydpi-v26/ic_launcher_round.xml
  - mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png        (48/72/96/144/192)
  - mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_round.png  (circle-masked)

Usage:
  python3 scripts/gen_icon.py            # generate everything
  python3 scripts/gen_icon.py --preview  # write /tmp/icon_preview*.png only
"""
import os
import sys
import argparse

import numpy as np
from PIL import Image, ImageDraw

# ---------------------------------------------------------------- palette (theme/Color.kt 纸墨令牌)
RED = (0xB9, 0x3B, 0x1D)          # LightPrimary 报纸红
PAPER = (0xFB, 0xFA, 0xF7)        # surface 纸白(头版卡面)
PAPER_WARM = (0xFF, 0xF8, 0xF4)   # LightOnPrimary 纸白(红底上的前景)
INK = (0x1B, 0x1A, 0x17)          # onSurface 墨色(报头/新闻行)

# master render resolution (downscaled for crisp anti-aliasing)
MASTER = 1536
ADAPTIVE_DP = 432                   # adaptive icon layer export size
LEGACY = {                          # density -> px (48dp base)
    "mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192,
}

RES = os.path.join("app", "src", "main", "res")


# ---------------------------------------------------------------- 碑刻衬线字形(单位坐标,y 向下)
def draw_A(draw, x, y, w, h, fg, bg):
    """罗马碑刻 A:外三角(fg)- 内三角(bg)+ 横档 + 双足衬线板。"""
    draw.polygon([(x + 0.5 * w, y), (x + w, y + h), (x, y + h)], fill=fg)
    draw.polygon([(x + 0.5 * w, y + 0.34 * h), (x + 0.75 * w, y + h), (x + 0.25 * w, y + h)], fill=bg)
    draw.rectangle([x + 0.355 * w, y + 0.60 * h, x + 0.645 * w, y + 0.74 * h], fill=fg)   # 横档
    draw.rectangle([x - 0.01 * w, y + 0.945 * h, x + 0.30 * w, y + h], fill=fg)            # 左足板
    draw.rectangle([x + 0.70 * w, y + 0.945 * h, x + 1.01 * w, y + h], fill=fg)            # 右足板


def draw_I(draw, x, y, w, h, fg):
    """罗马碑刻 I:竖干 + 上下衬线板(总宽 = 板宽)。"""
    draw.rectangle([x + 0.50 * w - 0.075 * h, y, x + 0.50 * w + 0.075 * h, y + h], fill=fg)
    draw.rectangle([x, y, x + w, y + 0.07 * h], fill=fg)
    draw.rectangle([x, y + 0.93 * h, x + w, y + h], fill=fg)


def draw_AI(draw, x, y, w, h, fg, bg):
    """'AI' 一行:A 宽 0.78h、I 板宽 0.34h、字距 0.16h,整体居中于给定框;返回包围盒。"""
    total = (0.78 + 0.16 + 0.34) * h
    if total > w:
        h = w / 1.28
        total = w
    a_w, i_w, gap = 0.78 * h, 0.34 * h, 0.16 * h
    x0 = x + (w - total) / 2
    draw_A(draw, x0, y, a_w, h, fg, bg)
    draw_I(draw, x0 + a_w + gap, y, i_w, h, fg)
    return (x0, y, x0 + total, y + h)


# ---------------------------------------------------------------- layers
def build_background(size):
    """报纸红纯色层(adaptive background)。"""
    arr = np.zeros((size, size, 4), dtype=np.uint8)
    arr[..., 0], arr[..., 1], arr[..., 2] = RED
    arr[..., 3] = 255
    return Image.fromarray(arr, "RGBA")


def build_foreground(size):
    """透明前景:红刊头底上的纸面「头版」—— AI 报头 + 双细线 + 红头条 + 新闻行。

    内容元素(报头/双线/头条/新闻行)全部落在 adaptive 安全区(中心 66% 圆)
    内,任何 launcher 遮罩都不会裁到内容;纸面卡片本身越出安全区,圆形遮罩
    会把四角裁成弧(文档类图标的通行做法)。
    """
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # 纸面头版卡片(微圆角)
    d.rounded_rectangle([size * 0.19, size * 0.155, size * 0.81, size * 0.845],
                        radius=size * 0.014, fill=PAPER + (255,))
    # 报头:墨字碑刻 AI + 双细线
    b = draw_AI(d, size * 0.31, size * 0.205, size * 0.38, size * 0.15,
                INK + (255,), PAPER + (255,))
    y = b[3] + size * 0.04
    t = size * 0.013
    g = size * 0.012
    d.rectangle([size * 0.27, y, size * 0.73, y + t], fill=INK + (255,))
    d.rectangle([size * 0.27, y + t + g, size * 0.73, y + 2 * t + g], fill=INK + (255,))
    # 头条(红)+ 新闻行(墨,长短错落)
    d.rectangle([size * 0.27, size * 0.455, size * 0.71, size * 0.505], fill=RED + (255,))
    d.rectangle([size * 0.27, size * 0.545, size * 0.73, size * 0.575], fill=INK + (255,))
    d.rectangle([size * 0.27, size * 0.605, size * 0.55, size * 0.635], fill=INK + (255,))
    d.rectangle([size * 0.27, size * 0.665, size * 0.62, size * 0.695], fill=INK + (255,))
    return img


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


def build_background_debug(size):
    """debug 专属背景:墨色纯色层(红=正式 / 墨=debug 的区分位)。"""
    arr = np.zeros((size, size, 4), dtype=np.uint8)
    arr[..., 0], arr[..., 1], arr[..., 2] = INK
    arr[..., 3] = 255
    return Image.fromarray(arr, "RGBA")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", action="store_true",
                    help="only write /tmp/icon_preview*.png and exit")
    ap.add_argument("--debug", action="store_true",
                    help="write debug source-set assets (ink seal) into app/src/debug/res")
    args = ap.parse_args()

    if args.debug:
        # debug 源集覆盖:墨底 + 同一头版前景(文件名沿用 ic_launcher_debug,
        # debug 的 mipmap-anydpi-v26/ic_launcher.xml 引用它)
        dbg = os.path.join(RES, "..", "..", "debug", "res")
        dxx = os.path.join(dbg, "drawable-xxxhdpi")
        os.makedirs(dxx, exist_ok=True)
        downscale(build_background_debug(MASTER), ADAPTIVE_DP).save(
            os.path.join(dxx, "ic_launcher_background.png"))
        downscale(build_foreground(MASTER), ADAPTIVE_DP).save(
            os.path.join(dxx, "ic_launcher_debug.png"))
        print("debug assets -> app/src/debug/res")
        return

    bg = build_background(MASTER)
    fg = build_foreground(MASTER)

    composite = Image.alpha_composite(
        bg.convert("RGBA"), fg.convert("RGBA")
    )

    if args.preview:
        out = "/tmp/icon_preview.png"
        composite.resize((512, 512), Image.LANCZOS).save(out)
        rnd = composite.resize((512, 512), Image.LANCZOS)
        arr = np.asarray(rnd).copy()
        arr[..., 3] = np.minimum(arr[..., 3], circle_mask(512))
        Image.fromarray(arr, "RGBA").save("/tmp/icon_preview_round.png")
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
