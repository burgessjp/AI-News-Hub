#!/usr/bin/env python3
"""
AI News Hub launcher icon generator (v1.4.0 纸墨日报).

Design: "星芒" (sparkle on a red seal)
  - Background: solid newspaper red (#B93B1D, LightPrimary of the paper-ink palette).
  - Foreground: paper (#FBFAF7) four-pointed sparkle —— astroid 星形线
    (x = a·cos³t, y = a·sin³t):四个锐利尖端 + 内凹弧边,业界通用的 AI 符号语汇。
    尖端半径 0.28×size:距典型圆形遮罩边缘(72dp 圆,半径 0.333×size)留
    ~0.053×size 清晰红边,且退回 66dp 安全区(半径 0.306×size)之内
    —— 0.322 的旧值越过了安全区,遮罩边缘只剩 ~1dp,视觉上星芒贴边。
    表意:AI 产品的通用符号;红印章底延续 App 纸墨色彩基因,与满屏蓝紫渐变的
    AI 图标区隔。

Debug variant (--debug): same sparkle on an ink (#1B1A17) seal ——
红 = 正式包 / 墨 = debug 包,纸墨双色系内一眼可分(配合 label 后缀 "(Debug)")。
Writes into app/src/debug/res (source-set overlay; pre-API-26 legacy mipmaps are
intentionally not overridden —— 老设备上 debug 与正式同图标,靠标签区分)。

  形状备注:星芒必须用 astroid 参数化的单一闭合多边形绘制。极坐标
  r = R·|cos2θ|^k 的低指数形态与贝塞尔透镜瓣都会外凸成「花瓣」,自交
  多边形会被 PIL 偶奇填充废掉 —— 三个坑都踩过,勿回退。

  The curve is parametric (no font/polygon-font machinery) — deterministic
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
import math
import os
import argparse

import numpy as np
from PIL import Image, ImageDraw

# ---------------------------------------------------------------- palette (theme/Color.kt 纸墨令牌)
RED = (0xB9, 0x3B, 0x1D)          # LightPrimary 报纸红
PAPER = (0xFB, 0xFA, 0xF7)        # surface 纸白(星芒)
INK = (0x1B, 0x1A, 0x17)          # onSurface 墨色(debug 印章底)

# 星芒尖端半径(占画布边长比例)。几何参照:66dp 安全区半径 = 0.306,
# 典型圆形遮罩边缘 = 0.333(72dp 圆)。取 0.28 —— 距遮罩边缘留 ~0.053
# 清晰红边,同时整体退回安全区内(此前 0.322 越界导致星芒视觉贴边)
TIP = 0.28

# master render resolution (downscaled for crisp anti-aliasing)
MASTER = 1536
ADAPTIVE_DP = 432                   # adaptive icon layer export size
LEGACY = {                          # density -> px (48dp base)
    "mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192,
}

RES = os.path.join("app", "src", "main", "res")


# ---------------------------------------------------------------- layers
def build_background(size):
    """报纸红纯色层(adaptive background)。"""
    arr = np.zeros((size, size, 4), dtype=np.uint8)
    arr[..., 0], arr[..., 1], arr[..., 2] = RED
    arr[..., 3] = 255
    return Image.fromarray(arr, "RGBA")


def build_foreground(size):
    """透明前景:纸白星形线(astroid)星芒,尖端半径 [TIP]×size,四重对称。"""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    c = size / 2
    a = TIP * size
    pts = [
        (c + a * math.cos(2 * math.pi * i / 720) ** 3,
         c + a * math.sin(2 * math.pi * i / 720) ** 3)
        for i in range(720)
    ]
    d.polygon(pts, fill=PAPER + (255,))
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
        # debug 源集覆盖:墨底 + 同一星芒前景(文件名沿用 ic_launcher_debug,
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
