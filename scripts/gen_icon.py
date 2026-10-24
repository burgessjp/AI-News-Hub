#!/usr/bin/env python3
"""
AI News Hub launcher icon generator.

Design v2: "热点雷达" (hot-topic radar on an ink seal)
  - Background: solid ink (#1B1A17, OnBackground 墨黑)。
  - Foreground: 纸金(#B98A4A, LightTertiaryContainer)同心弧圈雷达 ——
    内圈实线,中/外圈各留一道 70° 扫掠缺口(统一朝东北 315°),缺口中线
    上落一枚提亮报纸红(#E07A56, DarkPrimary)热点圆 + 同色回波细环,
    圆心一枚纸灰(#C9C4B8, DarkSecondary)定位点。
    表意:「探测正在变热的话题」——热榜 / 趋势词云的核心语义。
    替换 v1.4.0「星芒 ✦」:四角星已成 AI 图标泛滥符号;雷达保留纸墨
    基因,轮廓在货架上独有。

  几何(占画布边长比例,108dp 网格等比缩定,安全区校验):
    弧圈半径 0.083 / 0.152 / 0.220,线宽 0.023;缺口 70° @ 315°
    (PIL 角度:0° = 3 点钟方向,顺时针增大,与屏幕坐标一致)。
    热点圆心距中心 0.220(落在外圈缺口中线上),半径 0.038;
    回波环半径 0.068、线宽 0.011、α≈0.45;圆心定位点半径 0.023。
    最外沿 ≈ 0.220 + 0.068 + 0.005 = 0.293,退在 66dp 安全区
    (0.306)内,距典型圆形遮罩(0.333)留 ~4.3dp 清晰边 ——
    吸取星芒 0.322 贴边的教训,整组按 29→0.22 的比例收缩定稿。

Debug variant (--debug): 同款雷达换报纸红(#B93B1D)印章底,图形全部
改纸白 —— 墨 = 正式 / 红 = debug,纸墨双色系内一眼可分(配合 label
后缀 "(Debug)";v1 星芒时代是红 = 正式 / 墨 = debug,随正式包换墨底
对调)。Writes into app/src/debug/res (source-set overlay; pre-API-26
legacy mipmaps are intentionally not overridden —— 老设备上 debug 与
正式同图标,靠标签区分)。

Themed icon: adaptive XML 额外声明 <monochrome>(foreground 的 alpha
即雷达单色形;Android 13+ 随壁纸取色,低版本运行时忽略该节点)。

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
  python3 scripts/gen_icon.py --debug    # debug source-set assets
"""
import math
import os
import argparse

import numpy as np
from PIL import Image, ImageDraw

# ---------------------------------------------------------------- palette (theme/Color.kt 纸墨令牌)
RED = (0xB9, 0x3B, 0x1D)          # LightPrimary 报纸红(debug 印章底)
PAPER = (0xFB, 0xFA, 0xF7)        # surface 纸白(debug 图形)
INK = (0x1B, 0x1A, 0x17)          # OnBackground 墨黑(正式印章底)
GOLD = (0xB9, 0x8A, 0x4A)         # LightTertiaryContainer 纸金(弧圈)
PAPER_DIM = (0xC9, 0xC4, 0xB8)    # DarkSecondary 纸灰(圆心定位点)
RED_LIFT = (0xE0, 0x7A, 0x56)     # DarkPrimary 报纸红提亮(热点 / 回波)

# 雷达几何(占画布边长比例;推导见文件头「几何」注)
R_IN, R_MID, R_OUT = 0.083, 0.152, 0.220   # 三道弧圈半径(内圈实线)
RING_W = 0.023                              # 弧圈线宽
GAP_SPAN = 70.0                             # 扫掠缺口张角(度)
GAP_DIR = 315.0                             # 缺口朝向:东北(0°=3点钟,顺时针)
HOT_R = 0.038                               # 热点圆半径(圆心在外圈缺口中线上)
HALO_R = 0.068                              # 回波环半径
HALO_W = 0.011                              # 回波环线宽
HALO_ALPHA = 115                            # 回波环不透明度(≈0.45×255)
CENTER_R = 0.023                            # 圆心定位点半径

# master render resolution (downscaled for crisp anti-aliasing)
MASTER = 1536
ADAPTIVE_DP = 432                   # adaptive icon layer export size
LEGACY = {                          # density -> px (48dp base)
    "mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192,
}

RES = os.path.join("app", "src", "main", "res")


# ---------------------------------------------------------------- layers
def build_background(size):
    """墨黑纯色层(adaptive background,正式包印章底)。"""
    arr = np.zeros((size, size, 4), dtype=np.uint8)
    arr[..., 0], arr[..., 1], arr[..., 2] = INK
    arr[..., 3] = 255
    return Image.fromarray(arr, "RGBA")


def build_foreground(size, paper=False):
    """透明前景:纸金雷达弧圈 + 提亮红热点。

    paper=True 时图形全改纸白(debug 变体,配报纸红印章底)。
    PIL 的 arc/ellipse outline 自椭圆边界向内描边,偏差 ≤ 半线宽,
    经 MASTER→目标尺寸缩放后不可见。
    """
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    c = size / 2
    ring = PAPER if paper else GOLD
    mid = PAPER if paper else PAPER_DIM
    dot = PAPER if paper else RED_LIFT
    halo = PAPER + (HALO_ALPHA,) if paper else RED_LIFT + (HALO_ALPHA,)

    # 中 / 外圈带扫掠缺口:arc 自 start 顺时针画到 end,
    # 缺口以 GAP_DIR 为中心 → start = 350°, end = 280° + 360°
    start = GAP_DIR + GAP_SPAN / 2
    end = GAP_DIR - GAP_SPAN / 2 + 360
    for r in (R_MID, R_OUT):
        pr = r * size
        d.arc([c - pr, c - pr, c + pr, c + pr], start, end,
              fill=ring, width=round(RING_W * size))
    # 内圈实线
    pr = R_IN * size
    d.ellipse([c - pr, c - pr, c + pr, c + pr], outline=ring,
              width=round(RING_W * size))
    # 圆心定位点
    pr = CENTER_R * size
    d.ellipse([c - pr, c - pr, c + pr, c + pr], fill=mid)
    # 热点 + 回波环:圆心沿缺口中线(GAP_DIR 方向)落在外圈上
    ang = math.radians(GAP_DIR)
    dist = R_OUT * size
    hx = c + dist * math.cos(ang)
    hy = c + dist * math.sin(ang)
    pr = HALO_R * size
    d.ellipse([hx - pr, hy - pr, hx + pr, hy + pr], outline=halo,
              width=round(HALO_W * size))
    pr = HOT_R * size
    d.ellipse([hx - pr, hy - pr, hx + pr, hy + pr], fill=dot)
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
def write_adaptive_xml(path, fg, bg, mono=None):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    mono_line = (f"    <monochrome android:drawable=\"{mono}\" />\n"
                 if mono else "")
    with open(path, "w") as f:
        f.write(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            "<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
            f"    <background android:drawable=\"{bg}\" />\n"
            f"    <foreground android:drawable=\"{fg}\" />\n"
            f"{mono_line}"
            "</adaptive-icon>\n"
        )


def build_background_debug(size):
    """debug 专属背景:报纸红纯色层(墨 = 正式 / 红 = debug 的区分位)。"""
    arr = np.zeros((size, size, 4), dtype=np.uint8)
    arr[..., 0], arr[..., 1], arr[..., 2] = RED
    arr[..., 3] = 255
    return Image.fromarray(arr, "RGBA")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", action="store_true",
                    help="only write /tmp/icon_preview*.png and exit")
    ap.add_argument("--debug", action="store_true",
                    help="write debug source-set assets (red seal) into app/src/debug/res")
    args = ap.parse_args()

    if args.debug:
        # debug 源集覆盖:红底 + 纸白雷达前景(文件名沿用 ic_launcher_debug,
        # debug 的 mipmap-anydpi-v26/ic_launcher.xml 引用它)
        dbg = os.path.join(RES, "..", "..", "debug", "res")
        dxx = os.path.join(dbg, "drawable-xxxhdpi")
        os.makedirs(dxx, exist_ok=True)
        downscale(build_background_debug(MASTER), ADAPTIVE_DP).save(
            os.path.join(dxx, "ic_launcher_background.png"))
        downscale(build_foreground(MASTER, paper=True), ADAPTIVE_DP).save(
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

    # adaptive xml(含 themed icon 单色层)
    v26 = os.path.join(RES, "mipmap-anydpi-v26")
    write_adaptive_xml(
        os.path.join(v26, "ic_launcher.xml"),
        fg="@drawable/ic_launcher_foreground",
        bg="@drawable/ic_launcher_background",
        mono="@drawable/ic_launcher_foreground",
    )
    write_adaptive_xml(
        os.path.join(v26, "ic_launcher_round.xml"),
        fg="@drawable/ic_launcher_foreground",
        bg="@drawable/ic_launcher_background",
        mono="@drawable/ic_launcher_foreground",
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
