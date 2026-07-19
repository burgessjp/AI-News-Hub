#!/usr/bin/env python3
"""
AIHot 总览页 wordmark(Logo 字标)生成器。

设计:品牌「光环 + 核」图形(icon.svg 同源)+ Inter 字体的 "AI NEWS HUB" 字标
  - 左侧开口能量环 + 发光核,环描边走品牌双色渐变(Future Blue → Intelligence Purple)
  - "AI" 二字 Bold + 同款品牌渐变;"NEWS HUB" Medium + 宽字距,随深/浅主题的中性色
  - 渐变与中性色全部引用 @color(values / values-night 双份),深/浅主题自适应,
    与 Compose 侧 BrandGradient(primary→secondary)同套色值

输出(入库资源,重新运行即重新生成):
  - app/src/main/res/drawable/ic_wordmark.xml      VectorDrawable(字标文字已转路径)
  - app/src/main/res/values/colors.xml             wordmark 浅色配色
  - app/src/main/res/values-night/colors.xml       wordmark 深色配色
  - /tmp/wordmark_preview_{light,dark}.png         qlmanage 渲染预览

依赖:fontTools(pip install fontTools,见 scripts/requirements.txt);
预览渲染依赖 macOS qlmanage(与 gen_icon_svg.py 相同)。

用法:
  python3 scripts/gen_wordmark.py            # 生成资源 + 预览
  python3 scripts/gen_wordmark.py --preview-only   # 只出 /tmp 预览,不写 res/
"""
import argparse
import math
import os
import subprocess
import tempfile

from fontTools.misc.transform import Transform
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join("app", "src", "main", "res")
FONT_DIR = os.path.join(RES, "font")

# ===== 版面参数(viewport 单位,4 单位 = 1dp;总高 112 单位 = 28dp)=====
VH = 112.0
MARK_CX, MARK_CY = 44.0, 56.0     # 光环圆心
RING_R, RING_STROKE = 30.0, 6.5   # 能量环半径 / 描边宽
DOT_R = 13.0                      # 核心半径
HL_R = 3.7                        # 核心高光点
HL_DX, HL_DY = -3.9, -3.9         # 高光点相对圆心偏移(左上)
TEXT_X0 = 102.0                   # 字标起点 x(环右缘 + 间距)
CAP_H = 56.0                      # 大写字母高
BASELINE_Y = MARK_CY + CAP_H / 2  # 84.0

# 字标分段:(文本, 字体文件, 字距 em, 填充 "gradient" | "solid")
RUNS = [
    ("AI", "inter_bold.ttf", 0.04, "gradient"),
    ("NEWS HUB", "inter_semi_bold.ttf", 0.14, "solid"),
]
RUN_GAP_EXTRA_EM = 0.05           # 段间在空格宽度上追加的间距

# 品牌渐变(深/浅双份,与 ui/theme/Color.kt 色板一致)
GRADIENT = {
    "light": ("#003EC7", "#6B38D4"),   # primary → secondary (light)
    "dark": ("#B7C4FF", "#D0BCFF"),    # primary → secondary (dark)
}
WORDMARK_TEXT = {"light": "#141B2B", "dark": "#EDF0FF"}   # onSurface 两态
DOT_STOPS = ((0.0, "#FFFFFF"), (0.55, "#B7C4FF"), (1.0, "#003EC7"))  # 核心径向渐变(两态共用)


def arc_path(cx, cy, r, deg1, deg2, large_arc, sweep):
    """SVG/vector 圆弧 path:从 deg1 到 deg2(角度制,y 轴向下)。"""
    x1, y1 = cx + r * math.cos(math.radians(deg1)), cy + r * math.sin(math.radians(deg1))
    x2, y2 = cx + r * math.cos(math.radians(deg2)), cy + r * math.sin(math.radians(deg2))
    return (f"M {x1:.2f} {y1:.2f} A {r:.2f} {r:.2f} 0 {large_arc} {sweep} {x2:.2f} {y2:.2f}")


def circle_path(cx, cy, r):
    return (f"M {cx - r:.2f} {cy:.2f} "
            f"a {r:.2f} {r:.2f} 0 1 1 {2 * r:.2f} 0 "
            f"a {r:.2f} {r:.2f} 0 1 1 {-2 * r:.2f} 0 Z")


def layout_runs():
    """把各段文字转成带绝对坐标的 path 数据。

    返回 (glyphs, ai_span, total_w):
      glyphs   — [(path_data, fill_kind)]
      ai_span  — "AI" 段的 x 范围(渐变跨越整个段)
      total_w  — 字标总宽(含右侧收尾留白)
    """
    glyphs = []
    x = TEXT_X0
    ai_span = None
    for run_i, (text, font_file, tracking_em, fill) in enumerate(RUNS):
        font = TTFont(os.path.join(FONT_DIR, font_file))
        glyph_set = font.getGlyphSet()
        cmap = font.getBestCmap()
        upm = font["head"].unitsPerEm
        scale = CAP_H / font["OS/2"].sCapHeight
        if run_i > 0:
            # 段间距 = 一个空格宽 + 额外间距(按本段字号折算)
            space_w = glyph_set[cmap[ord(" ")]].width * (CAP_H / upm)
            x += space_w + RUN_GAP_EXTRA_EM * CAP_H
        run_start = x
        for ch_i, ch in enumerate(text):
            glyph = glyph_set[cmap[ord(ch)]]
            advance = glyph.width * (CAP_H / upm)
            if ch != " ":
                pen = SVGPathPen(glyph_set)
                tp = TransformPen(pen, Transform(scale, 0, 0, -scale, round(x, 2), BASELINE_Y))
                glyph.draw(tp)
                glyphs.append((pen.getCommands(), fill))
            x += advance
            if ch_i < len(text) - 1:
                x += tracking_em * CAP_H
        if run_i == 0:
            ai_span = (run_start, x)
    return glyphs, ai_span, x + 8.0  # 右侧收尾留白 8 单位


def gradient_stops_xml(stops, indent):
    pad = " " * indent
    return "\n".join(
        f'{pad}<item android:offset="{off:g}" android:color="{color}"/>' for off, color in stops
    )


def build_vector(glyphs, ai_span, total_w):
    """生成 VectorDrawable XML(颜色全部走 @color,深/浅自适应)。"""
    ring = arc_path(MARK_CX, MARK_CY, RING_R, -110, 130, 1, 1)  # 右下留 ~120° 开口
    dot = circle_path(MARK_CX, MARK_CY, DOT_R)
    hl = circle_path(MARK_CX + HL_DX, MARK_CY + HL_DY, HL_R)
    gx1, gy1, gx2, gy2 = MARK_CX - 20, MARK_CY - 28, MARK_CX + 20, MARK_CY + 28
    paths = [
        f'''    <!-- 能量环:开口在右下,描边品牌渐变 -->
    <path android:pathData="{ring}"
          android:strokeWidth="{RING_STROKE}" android:strokeLineCap="round">
        <aapt:attr name="android:strokeColor">
            <gradient android:type="linear"
                android:startX="{gx1:g}" android:startY="{gy1:g}"
                android:endX="{gx2:g}" android:endY="{gy2:g}">
{gradient_stops_xml([(0.0, "@color/wordmark_gradient_start"), (1.0, "@color/wordmark_gradient_end")], 16)}
            </gradient>
        </aapt:attr>
    </path>

    <!-- 中心核:径向渐变 -->
    <path android:pathData="{dot}">
        <aapt:attr name="android:fillColor">
            <gradient android:type="radial"
                android:centerX="{MARK_CX:g}" android:centerY="{MARK_CY:g}"
                android:gradientRadius="{DOT_R:g}">
{gradient_stops_xml([(o, c) for o, c in DOT_STOPS], 16)}
            </gradient>
        </aapt:attr>
    </path>

    <!-- 核心高光点 -->
    <path android:pathData="{hl}" android:fillColor="#FFFFFF" android:fillAlpha="0.92"/>
'''
    ]
    ai_g = [(0.0, "@color/wordmark_gradient_start"), (1.0, "@color/wordmark_gradient_end")]
    for data, fill in glyphs:
        if fill == "gradient":
            paths.append(f'''    <path android:pathData="{data}">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear"
                android:startX="{ai_span[0]:.2f}" android:startY="{BASELINE_Y - CAP_H:.2f}"
                android:endX="{ai_span[1]:.2f}" android:endY="{BASELINE_Y:.2f}">
{gradient_stops_xml(ai_g, 16)}
            </gradient>
        </aapt:attr>
    </path>
''')
        else:
            paths.append(f'    <path android:pathData="{data}" android:fillColor="@color/wordmark_text"/>\n')
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- 总览页 wordmark("AI NEWS HUB" Logo 字标)—— 由 scripts/gen_wordmark.py 生成,勿手改 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="{total_w / 4:.1f}dp"
    android:height="{VH / 4:.1f}dp"
    android:viewportWidth="{total_w:.2f}"
    android:viewportHeight="{VH:g}">
{"".join(paths)}</vector>
'''


def build_preview_svg(glyphs, ai_span, total_w, theme):
    """与 vector 同版面的 SVG 预览(渐变 userSpaceOnUse 对齐坐标)。"""
    g0, g1 = GRADIENT[theme]
    text_color = WORDMARK_TEXT[theme]
    bg = "#F9F9FF" if theme == "light" else "#11132A"
    dot_stops = "".join(f'<stop offset="{o:g}" stop-color="{c}"/>' for o, c in DOT_STOPS)
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {total_w:.2f} {VH:g}" '
        f'width="{total_w * 4:.0f}" height="{VH * 4:.0f}">',
        '<defs>',
        f'<linearGradient id="arc" gradientUnits="userSpaceOnUse" '
        f'x1="{MARK_CX - 20}" y1="{MARK_CY - 28}" x2="{MARK_CX + 20}" y2="{MARK_CY + 28}">'
        f'<stop offset="0" stop-color="{g0}"/><stop offset="1" stop-color="{g1}"/></linearGradient>',
        f'<linearGradient id="ai" gradientUnits="userSpaceOnUse" '
        f'x1="{ai_span[0]:.2f}" y1="{BASELINE_Y - CAP_H:.2f}" x2="{ai_span[1]:.2f}" y2="{BASELINE_Y:.2f}">'
        f'<stop offset="0" stop-color="{g0}"/><stop offset="1" stop-color="{g1}"/></linearGradient>',
        f'<radialGradient id="dot" gradientUnits="userSpaceOnUse" '
        f'cx="{MARK_CX}" cy="{MARK_CY}" r="{DOT_R}">{dot_stops}</radialGradient>',
        '</defs>',
        f'<rect width="{total_w:.2f}" height="{VH:g}" fill="{bg}"/>',
        f'<path d="{arc_path(MARK_CX, MARK_CY, RING_R, -110, 130, 1, 1)}" fill="none" '
        f'stroke="url(#arc)" stroke-width="{RING_STROKE}" stroke-linecap="round"/>',
        f'<path d="{circle_path(MARK_CX, MARK_CY, DOT_R)}" fill="url(#dot)"/>',
        f'<path d="{circle_path(MARK_CX + HL_DX, MARK_CY + HL_DY, HL_R)}" fill="#FFFFFF" opacity="0.92"/>',
    ]
    for data, fill in glyphs:
        color = "url(#ai)" if fill == "gradient" else text_color
        parts.append(f'<path d="{data}" fill="{color}"/>')
    parts.append("</svg>")
    return "\n".join(parts)


CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"


def render_preview(svg_text, out_png, total_w):
    """优先用 headless Chrome 精确渲染(qlmanage 对非方形 SVG 会输出方形画布,仅作兜底)。"""
    w, h = int(total_w * 4), int(VH * 4)
    if os.path.exists(CHROME):
        tmp = tempfile.NamedTemporaryFile("w", suffix=".html", delete=False)
        tmp.write(f'<html><body style="margin:0">{svg_text}</body></html>')
        tmp.close()
        subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars",
                        f"--screenshot={out_png}", f"--window-size={w},{h}",
                        "file://" + tmp.name], check=True, capture_output=True)
        os.unlink(tmp.name)
        return
    tmp = tempfile.NamedTemporaryFile("w", suffix=".svg", delete=False)
    tmp.write(svg_text)
    tmp.close()
    tmp_out = tempfile.mkdtemp()
    subprocess.run(["qlmanage", "-t", "-s", str(w), "-o", tmp_out, tmp.name],
                   check=True, capture_output=True)
    os.rename(os.path.join(tmp_out, os.path.basename(tmp.name) + ".png"), out_png)
    os.unlink(tmp.name)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview-only", action="store_true", help="只渲染 /tmp 预览,不写 res/")
    args = ap.parse_args()

    glyphs, ai_span, total_w = layout_runs()
    print(f"viewport: {total_w:.2f} x {VH:g} ({len(glyphs)} glyph paths)")

    for theme in ("light", "dark"):
        svg = build_preview_svg(glyphs, ai_span, total_w, theme)
        out = f"/tmp/wordmark_preview_{theme}.png"
        render_preview(svg, out, total_w)
        print(f"preview -> {out}")

    if args.preview_only:
        return

    drawable = os.path.join(RES, "drawable", "ic_wordmark.xml")
    os.makedirs(os.path.dirname(drawable), exist_ok=True)
    with open(drawable, "w") as f:
        f.write(build_vector(glyphs, ai_span, total_w))
    print(f"drawable -> {drawable}")

    for qualifier, theme in (("values", "light"), ("values-night", "dark")):
        g0, g1 = GRADIENT[theme]
        path = os.path.join(RES, qualifier, "colors.xml")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            f.write(f'''<?xml version="1.0" encoding="utf-8"?>
<!-- 总览页 wordmark 配色({theme})—— 由 scripts/gen_wordmark.py 生成;
     色值与 ui/theme/Color.kt {"浅色" if theme == "light" else "深色"}色板的 primary/secondary/onSurface 保持一致 -->
<resources>
    <color name="wordmark_gradient_start">{g0}</color>
    <color name="wordmark_gradient_end">{g1}</color>
    <color name="wordmark_text">{WORDMARK_TEXT[theme]}</color>
</resources>
''')
        print(f"colors   -> {path}")


if __name__ == "__main__":
    main()
