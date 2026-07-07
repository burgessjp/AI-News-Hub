#!/usr/bin/env python3
"""
AIHot launcher icon generator (SVG-based, hand-crafted design).

Design: "光环 + 核" (open energy ring + glowing core)
  - 圆角方形深色 teal→近黑 背景
  - cyan 渐变开口能量环 (右下留开口，破除靶心感)
  - 中心发光核 + 高光点

Source of truth: scripts/icon.svg  (hand-authored)
Renders via macOS qlmanage (SVG → PNG), then PIL resizes for each density.

Outputs (under app/src/main/res):
  - drawable-xxxhdpi/ic_launcher_background.png  (432px adaptive bg layer)
  - drawable-xxxhdpi/ic_launcher_foreground.png  (432px adaptive fg layer, transparent)
  - mipmap-anydpi-v26/ic_launcher.xml
  - mipmap-anydpi-v26/ic_launcher_round.xml
  - mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png        (48/72/96/144/192)
  - mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher_round.png  (circle-masked)

Usage:
  python3 scripts/gen_icon_svg.py             # full set
  python3 scripts/gen_icon_svg.py --preview   # /tmp/svg_preview.png only
"""
import os
import subprocess
import argparse
import tempfile

from PIL import Image, ImageDraw, ImageFilter
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
SVG = os.path.join(HERE, "icon.svg")
RES = os.path.join("app", "src", "main", "res")

ADAPTIVE_DP = 432
LEGACY = {  # density -> px (48dp base)
    "mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192,
}


def render_svg(svg_path, out_png, size):
    """Render SVG to PNG at given size via qlmanage."""
    tmp_out = tempfile.mkdtemp()
    # qlmanage renders at thumbnail size; render 2x for quality then downscale
    render_size = max(size, 256) * 3
    subprocess.run(
        ["qlmanage", "-t", "-s", str(render_size), "-o", tmp_out, svg_path],
        check=True, capture_output=True,
    )
    produced = os.path.join(tmp_out, os.path.basename(svg_path) + ".png")
    img = Image.open(produced).convert("RGBA")
    img = img.resize((size, size), Image.LANCZOS)
    # write to a temp then we return image object; caller saves
    os.remove(produced)
    os.rmdir(tmp_out)
    return img


def make_background_layer(size):
    """
    Adaptive background layer: 圆角方形深色渐变铺满 (无前景元素)。
    因为自适应图标的背景应填满整个画布（前景才放进 66% 安全区）。
    """
    svg_bg = f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="512" height="512">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#103742"/>
      <stop offset="0.55" stop-color="#0A1B22"/>
      <stop offset="1" stop-color="#05080D"/>
    </linearGradient>
  </defs>
  <rect x="0" y="0" width="512" height="512" fill="url(#bg)"/>
</svg>"""
    tmp = tempfile.NamedTemporaryFile("w", suffix=".svg", delete=False)
    tmp.write(svg_bg)
    tmp.close()
    img = render_svg(tmp.name, None, size)
    os.unlink(tmp.name)
    return img


def make_foreground_layer(size):
    """
    Adaptive foreground layer: 透明背景上的光环+核图形，
    已缩放进 ~66% 安全区 (Android adaptive icon requirement).
    """
    # 主 SVG 图形居中且已留白，但自适应图标要求前景在中心 66dp 安全区内
    # (即 66/108 ≈ 61% 的画布)。我们把整个图形缩放到 66% 居中放置。
    full = render_svg(SVG, None, size)
    # 创建透明画布，把 full 缩到 66% 居中贴上
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    scale = 0.66 * 1.06  # 略大于 66% 让图形饱满一点，仍安全
    nw = int(size * scale)
    scaled = full.resize((nw, nw), Image.LANCZOS)
    # 但 full 本身是带圆角方形背景的整图；前景层我们要去掉背景只留图形
    # —— 简化：自适应图标前景允许包含背景，系统会用蒙版；
    #          但更干净的做法是前景只放图形（透明底）。
    # 这里我们重新渲染一个"只有图形、透明底、居中安全区"的 SVG。
    return _make_fg_transparent(size)


def _make_fg_transparent(size):
    """渲染透明底的前景图形（光环+核），缩放进安全区。"""
    svg_fg = f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" width="512" height="512">
  <defs>
    <linearGradient id="arc" x1="0.2" y1="0" x2="0.8" y2="1">
      <stop offset="0" stop-color="#CFFAFE"/>
      <stop offset="0.45" stop-color="#22D3EE"/>
      <stop offset="1" stop-color="#0891B2"/>
    </linearGradient>
    <radialGradient id="dot" cx="0.4" cy="0.4" r="0.6">
      <stop offset="0" stop-color="#FFFFFF"/>
      <stop offset="0.5" stop-color="#A5F3FC"/>
      <stop offset="1" stop-color="#22D3EE"/>
    </radialGradient>
  </defs>
  <circle cx="256" cy="256" r="214" fill="#22D3EE" opacity="0.035"/>
  <circle cx="256" cy="256" r="184" fill="#22D3EE" opacity="0.06"/>
  <circle cx="256" cy="256" r="156" fill="#22D3EE" opacity="0.085"/>
  <circle cx="256" cy="256" r="142" fill="#67E8F9" opacity="0.14"/>
  <circle cx="256" cy="256" r="132" fill="#67E8F9" opacity="0.18"/>
  <path d="M 214.3 141.3 A 122 122 0 1 1 177.5 349.5"
        fill="none" stroke="url(#arc)" stroke-width="22" stroke-linecap="round"/>
  <path d="M 141.3 214.3 A 122 122 0 0 1 317.0 150.3"
        fill="none" stroke="#ECFEFF" stroke-width="7" stroke-linecap="round" opacity="0.9"/>
  <circle cx="256" cy="256" r="70" fill="#A5F3FC" opacity="0.18"/>
  <circle cx="256" cy="256" r="52" fill="url(#dot)"/>
  <circle cx="240" cy="240" r="15" fill="#FFFFFF" opacity="0.92"/>
</svg>"""
    tmp = tempfile.NamedTemporaryFile("w", suffix=".svg", delete=False)
    tmp.write(svg_fg)
    tmp.close()
    img = render_svg(tmp.name, None, size)
    os.unlink(tmp.name)
    return img


def circle_mask(size, feather=0):
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    cx = cy = (size - 1) / 2.0
    r = size / 2.0
    a = np.clip(r - np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2), 0, 1)
    if feather:
        pass
    return (a * 255).astype(np.uint8)


def write_adaptive_xml(path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            "<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
            "    <background android:drawable=\"@drawable/ic_launcher_background\" />\n"
            "    <foreground android:drawable=\"@drawable/ic_launcher_foreground\" />\n"
            "</adaptive-icon>\n"
        )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", action="store_true")
    args = ap.parse_args()

    full = render_svg(SVG, None, 1024)

    if args.preview:
        out = "/tmp/svg_preview.png"
        # square + round
        full.resize((512, 512), Image.LANCZOS).save(out)
        rnd = full.resize((512, 512), Image.LANCZOS).copy()
        arr = np.asarray(rnd).copy()
        arr[..., 3] = np.minimum(arr[..., 3], circle_mask(512))
        Image.fromarray(arr, "RGBA").save("/tmp/svg_preview_round.png")
        print("preview -> /tmp/svg_preview*.png")
        return

    # adaptive layers
    dxx = os.path.join(RES, "drawable-xxxhdpi")
    os.makedirs(dxx, exist_ok=True)
    bg = make_background_layer(ADAPTIVE_DP)
    fg = _make_fg_transparent(ADAPTIVE_DP)
    bg.save(os.path.join(dxx, "ic_launcher_background.png"))
    fg.save(os.path.join(dxx, "ic_launcher_foreground.png"))

    # adaptive xml
    v26 = os.path.join(RES, "mipmap-anydpi-v26")
    write_adaptive_xml(os.path.join(v26, "ic_launcher.xml"))
    write_adaptive_xml(os.path.join(v26, "ic_launcher_round.xml"))

    # legacy PNGs (full composite) for each density
    for density, px in LEGACY.items():
        d = os.path.join(RES, f"mipmap-{density}")
        os.makedirs(d, exist_ok=True)
        sq = full.resize((px, px), Image.LANCZOS)
        sq.save(os.path.join(d, "ic_launcher.png"))
        # round variant
        arr = np.asarray(sq).copy()
        arr[..., 3] = (np.asarray(sq)[..., 3].astype(np.float32) / 255.0
                       * circle_mask(px).astype(np.float32) / 255.0 * 255).astype(np.uint8)
        Image.fromarray(arr, "RGBA").save(os.path.join(d, "ic_launcher_round.png"))

    print("done.")


if __name__ == "__main__":
    main()
