#!/usr/bin/env python3
"""AI News Hub 创意版宣传片 · 动效帧渲染(PIL 逐帧)。

4K(3840x2160)超采样绘制 → LANCZOS 缩至 1920x1080,输出 JPEG(q93 / 4:4:4)。
在仓库根目录执行:scripts/.venv/bin/python docs/promo/render_motion.py [--only intro|outro|bg|<seg>]
版面段(bgm_*)段首约 0.6s 文字滑入,之后为静态尾帧(直接复制字节,不重渲)。
坐标按 1920x1080 逻辑书写;视觉常量内联自 design/promo/render_assets.py(报纸三色)。
中间产物(frames/,~235MB)输出到 design/promo2/ 工作区(gitignore),不进 docs/。
"""
import sys, os, math, argparse, shutil
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
FR   = os.path.join(ROOT, "design", "promo2", "frames")

# ---- 视觉常量(源自 design/promo/render_assets.py,报纸三色 + 系统字体) ----
PAPER = (250, 247, 242, 255)      # 纸白
INK   = (28, 25, 23, 255)         # 墨色
RED   = (185, 59, 29, 255)        # 报纸红
GOLD  = (156, 107, 47, 255)       # 纸金
INK_SOFT = (28, 25, 23, 160)      # 墨色 60%
SONG_BLACK = ("/System/Library/Fonts/Supplemental/Songti.ttc", 0)
HIRA_W3 = ("/System/Library/Fonts/Hiragino Sans GB.ttc", 0)
HIRA_W6 = ("/System/Library/Fonts/Hiragino Sans GB.ttc", 2)
ICON = "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png"   # 相对仓库根

def font(spec, size):
    from PIL import ImageFont
    return ImageFont.truetype(spec[0], size, index=spec[1])

def paper_bg(w, h, grain=True):
    """纸面:纸白底 + 4.5% 噪点颗粒。"""
    im = Image.new("RGB", (w, h), PAPER[:3])
    if grain:
        noise = Image.effect_noise((w, h), 22).convert("RGB")
        im = Image.blend(im, noise, 0.045)
    return im.convert("RGBA")

def rounded_shadow(base, box, radius, blur=18, alpha=70, offset=(0, 6)):
    """在 base 上为圆角矩形画柔和投影,返回新图。"""
    x0, y0, x1, y1 = box
    sh = Image.new("RGBA", base.size, (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle(
        [x0 + offset[0], y0 + offset[1], x1 + offset[0], y1 + offset[1]],
        radius=radius, fill=(20, 16, 12, alpha))
    return Image.alpha_composite(base, sh.filter(ImageFilter.GaussianBlur(blur)))

FPS    = 30
LW, LH = 1920, 1080          # 逻辑分辨率
S      = 2                   # 超采样倍率
W, H   = LW * S, LH * S

# 预混实色(画布上直接替换像素,半透明 tuple 会在 RGBA 画布上砸出透明洞)
INK_90  = tuple(int(INK[i] * 0.55 + PAPER[i] * 0.45) for i in range(3))   # 内框/小字灰(视频二压后仍可读)
INK_70  = tuple(int(INK[i] * 0.45 + PAPER[i] * 0.55) for i in range(3))   # 槽位描边灰
TILE_BG = tuple(int(INK[i] * 0.07 + PAPER[i] * 0.93) for i in range(3))   # 铅字淡灰底

SOURCES = ["HackerNews", "GitHub Trending", "HuggingFace Papers", "Product Hunt",
           "The Rundown", "OpenAI", "Anthropic", "AIHot Picks"]
# 开场锁定的今日真实 Top 3(与 clean/seg_today.mp4 画面一致;重录换日后需同步更新)
TOP3 = ["OpenAI 红队测试发现 Brotli 未认证远程代码执行漏洞并已披露",
        "Show HN: Steal My Bitcoin",
        "1Password for Agents"]

BG_SEGS = [("launch", 3.2), ("today", 4.4), ("weekly", 3.0),
           ("trends", 6.6), ("read", 5.6), ("dark", 4.2)]
STILL_F = 24            # 版面段动画结束帧,之后复制静态帧
ANIM_DUR = 7            # 每行动画时长(帧)

# ---------- 缓动 ----------
def clamp01(x): return 0.0 if x < 0 else (1.0 if x > 1 else x)
def ease_out_cubic(t): t = clamp01(t); return 1 - (1 - t) ** 3
def ease_io_cubic(t):
    t = clamp01(t)
    return 4 * t * t * t if t < .5 else 1 - ((-2 * t + 2) ** 3) / 2

# ---------- 字体 / 图层 ----------
def F(spec, size): return font(spec, int(size * S))

def text_layer(text, spec, size, fill, tracking=0):
    """文字画到最小透明层(2x),返回 (layer, w, h) 逻辑尺寸。"""
    f = F(spec, size)
    tmp = ImageDraw.Draw(Image.new("RGBA", (8, 8)))
    if tracking:
        tw = sum(tmp.textlength(ch, font=f) for ch in text) + (tracking * S) * (len(text) - 1)
    else:
        tw = tmp.textlength(text, font=f)
    asc, desc = f.getmetrics()
    lay = Image.new("RGBA", (int(tw) + 4 * S, asc + desc + 2 * S), (0, 0, 0, 0))
    d = ImageDraw.Draw(lay)
    x = 2 * S
    for ch in text:
        d.text((x, 0), ch, font=f, fill=fill)
        x += tmp.textlength(ch, font=f) + tracking * S
    return lay, int(tw) // S, (asc + desc) // S

def paste_faded(base, layer, pos, p, dx=0, dy=0):
    """按进度 p 合成图层:透明度 p,位移 (dx,dy)*(1-p);pos/dx/dy 均为逻辑坐标。"""
    if p <= 0: return
    x = int((pos[0] + dx * (1 - p)) * S)
    y = int((pos[1] + dy * (1 - p)) * S)
    if p < 1:
        layer = layer.copy()
        layer.putalpha(layer.getchannel("A").point(lambda v: int(v * p)))
    base.alpha_composite(layer, (x, y))

# ---------- 公共底图 ----------
_PLAIN = None
def plain():
    global _PLAIN
    if _PLAIN is None:
        _PLAIN = paper_bg(W, H)
    return _PLAIN.copy()

def border(d, p=1.0):
    """双细框按周长进度顺时针画出(自左上角);p>=1 为完整框。"""
    def pts(x0, y0, x1, y1):
        seg = [(x0, y0, x1, y0), (x1, y0, x1, y1), (x1, y1, x0, y1), (x0, y1, x0, y0)]
        out = []
        for ax, ay, bx, by in seg:
            n = max(2, int(max(abs(bx - ax), abs(by - ay)) / (40 * S)))
            out += [(ax + (bx - ax) * i / n, ay + (by - ay) * i / n) for i in range(n)]
        return out
    q = clamp01(p)
    if q > 0:
        pp = pts(28 * S, 28 * S, W - 28 * S, H - 28 * S)
        d.line(pp[:max(2, int(len(pp) * q))], fill=INK, width=3 * S, joint="curve")
    q2 = clamp01(p * 1.6 - 0.6)
    if q2 > 0:
        pp = pts(40 * S, 40 * S, W - 40 * S, H - 40 * S)
        d.line(pp[:max(2, int(len(pp) * q2))], fill=INK_90, width=S, joint="curve")

def kicker_rule(d, x, y, w, p=1.0):
    """红粗规线 + 墨细规线,宽度按进度左→右画出(逻辑坐标)。"""
    ww = int(w * S * clamp01(p))
    if ww > 0:
        d.rectangle([x * S, y * S, x * S + ww, (y + 16) * S], fill=RED)
        if clamp01(p) >= 1:
            d.line([x * S, (y + 34) * S, x * S + ww, (y + 34) * S], fill=INK_90, width=2 * S)

def hline(d, x, y, w, p=1.0):
    ww = int(w * S * clamp01(p))
    if ww > 0:
        d.line([x * S, y * S, x * S + ww, y * S], fill=INK_90, width=2 * S)

# ---------- 输出 ----------
def finish(im, zoom_p=0.0, zoom_amt=0.03, white_p=0.0, fade_p=0.0,
           fade_rgb=PAPER[:3], fade_amt=1.0):
    if zoom_p > 0:
        z = 1 + zoom_amt * ease_io_cubic(zoom_p)
        cw, ch = int(W / z), int(H / z)
        x0, y0 = (W - cw) // 2, (H - ch) // 2
        im = im.crop((x0, y0, x0 + cw, y0 + ch))
    im = im.resize((LW, LH), Image.LANCZOS).convert("RGB")
    if white_p > 0:
        im = Image.blend(im, Image.new("RGB", im.size, (255, 255, 255)), ease_io_cubic(white_p))
    if fade_p > 0:
        im = Image.blend(im, Image.new("RGB", im.size, fade_rgb), fade_amt * ease_io_cubic(fade_p))
    return im

def emit(im, path, **kw):
    finish(im, **kw).save(path, quality=93, subsampling=0)

# ================= 开场(180 帧 / 6.0s) =================
def make_tile(name):
    """铅字块:淡灰圆角底 + 墨色源名(2x 图层)。"""
    lay, tw, th = text_layer(name, HIRA_W6, 34, INK)
    pad = 22 * S
    tile = Image.new("RGBA", (lay.width + pad * 2, lay.height + pad), (0, 0, 0, 0))
    d = ImageDraw.Draw(tile)
    d.rounded_rectangle([0, 0, tile.width - 1, tile.height - 1],
                        radius=9 * S, fill=TILE_BG + (255,))
    tile.alpha_composite(lay, (pad, 0))
    return tile

def draw_radar(d, cx, cy, R, p_arcs, scan_deg=None):
    """纸金同心弧逐圈画出 + 可选红色扫描线(逻辑坐标)。cx/cy/R 逻辑。"""
    for j in range(3):
        pj = clamp01((p_arcs - j * 0.22) / 0.56)
        if pj <= 0: continue
        r = R * (0.52 + 0.27 * j) * S
        a0 = -90 + 120 * j
        d.arc([cx * S - r, cy * S - r, cx * S + r, cy * S + r],
              a0, a0 + 285 * ease_out_cubic(pj), fill=GOLD, width=(13 - 3 * j) * S)
    if scan_deg is not None:
        th = math.radians(scan_deg)
        x1, y1 = cx * S + math.cos(th) * R * 1.32 * S, cy * S + math.sin(th) * R * 1.32 * S
        d.line([cx * S, cy * S, x1, y1], fill=RED, width=5 * S)
        d.ellipse([x1 - 7 * S, y1 - 7 * S, x1 + 7 * S, y1 + 7 * S], fill=RED)

def render_intro():
    n = 180
    tiles = [make_tile(s) for s in SOURCES]
    tgt = [(470, 240), (950, 170), (1430, 250), (310, 540),
           (1600, 560), (540, 860), (1040, 770), (1490, 850)]
    phase = [0, .3, .6, .9, 1.2, 1.5, 1.8, 2.1]
    # 入射方向:版心→散布点的射线向外延伸
    starts = []
    for (tx, ty) in tgt:
        vx, vy = tx - 960, ty - 540
        L = math.hypot(vx, vy) or 1
        starts.append((tx + vx / L * 1500, ty + vy / L * 1500))
    # 印刷文字的缓存图层
    kick, _, _ = text_layer("AI NEWS HUB", HIRA_W6, 46, RED, tracking=24)
    def char_layers(text, y):
        f = F(SONG_BLACK, 116)
        m = ImageDraw.Draw(Image.new("RGBA", (8, 8)))
        out, x = [], 150
        for ch in text:
            wch = m.textlength(ch, font=f) / S
            if ch != " ":
                out.append((ch, x, y))
            x += wch
        return out
    t1, t2 = char_layers("全网 AI 热点", 336), char_layers("一屏尽览", 496)
    cl = {}
    for ch, x, y in t1 + t2:
        if ch not in cl:
            cl[ch] = text_layer(ch, SONG_BLACK, 116, INK)[0]
    kick3, _, _ = text_layer("今日重点 · TOP 3", HIRA_W6, 30, RED, tracking=6)
    ranks = [text_layer(str(i + 1), SONG_BLACK, 50, RED)[0] for i in range(3)]
    titles = [text_layer(t, HIRA_W6, 38, INK)[0] for t in TOP3]

    for i in range(n):
        im = plain()
        d = ImageDraw.Draw(im)
        border(d, clamp01(i / 18))
        # -- 印刷:眉题/大标题/规线 (f84-128)
        paste_faded(im, kick, (150, 210), ease_out_cubic((i - 84) / 8), dy=-14)
        hline(d, 150, 296, 560, clamp01((i - 88) / 8))
        for k, (ch, x, y) in enumerate(t1):
            p = ease_out_cubic((i - (92 + k * 2)) / 5)
            paste_faded(im, cl[ch], (x, y), p, dy=16)
        for k, (ch, x, y) in enumerate(t2):
            p = ease_out_cubic((i - (108 + k * 2)) / 5)
            paste_faded(im, cl[ch], (x, y), p, dy=16)
        kicker_rule(d, 150, 680, 560, ease_out_cubic((i - 118) / 10))
        # -- 雷达弧 + 扫描线 (f102-156)
        draw_radar(d, 1440, 430, 170, clamp01((i - 102) / 30),
                   scan_deg=(-90 + 360 * clamp01((i - 126) / 30)) if i >= 126 else None)
        if i >= 120:
            r = 11 * S
            d.ellipse([1440 * S - r, 430 * S - r, 1440 * S + r, 430 * S + r], fill=RED)
        # -- 锁定 Top 3 (f122-,扫描过程中逐条弹出)
        paste_faded(im, kick3, (150, 738), ease_out_cubic((i - 122) / 8), dx=-24)
        for j in range(3):
            p = ease_out_cubic((i - (128 + 12 * j)) / 8)
            if p <= 0: continue
            y = 786 + 84 * j
            if p < 1:  # 锁定环收缩
                rr = (30 + (10 - 30) * p) * S
                c = tuple(int(RED[k] * p + PAPER[k] * (1 - p)) for k in range(3))
                d.ellipse([174 * S - rr, (y + 24) * S - rr, 174 * S + rr, (y + 24) * S + rr],
                          outline=c, width=3 * S)
            paste_faded(im, ranks[j], (150, y), p, dx=-14)
            paste_faded(im, titles[j], (225, y + 4), p, dx=24)
        # -- 铅字块(最后画,熔版时压在标题上层)
        for j, tile in enumerate(tiles):
            p_in = ease_out_cubic((i - (6 + j * 4)) / 27)
            if p_in <= 0 or i >= 91: continue
            tx, ty = tgt[j]
            if i < 54:   # 飞入 + 漂浮
                sx, sy = starts[j]
                x = sx + (tx - sx) * p_in
                y = sy + (ty - sy) * p_in + (8 * math.sin(2 * math.pi * (i / 66 + phase[j])) if p_in >= 1 else 0)
                rot = (4 if j % 2 else -4) + 3 * math.sin(i / 50 + phase[j])
                scale, alpha = 1.0, 1.0
            else:        # 熔进版面:向版心收拢缩小淡出
                m = ease_io_cubic((i - 54) / 36)
                x, y = tx + (960 - tx) * m, ty + (540 - ty) * m
                rot, scale = 0, 1 - 0.85 * m
                alpha = 1 - clamp01((m - 0.45) / 0.55)
            t = tile
            if scale != 1:
                t = t.resize((max(2, int(t.width * scale)), max(2, int(t.height * scale))),
                             Image.LANCZOS)
            if rot: t = t.rotate(rot, expand=True, resample=Image.BICUBIC)
            if alpha < 1: t = t.copy(); t.putalpha(t.getchannel("A").point(lambda v: int(v * alpha)))
            im.alpha_composite(t, (int(x * S - t.width / 2), int(y * S - t.height / 2)))
        # -- 纸面淡入 / 缓推 + 闪白
        kw = {}
        if i < 12: kw.update(white_p=1 - i / 12)
        if i >= 162: kw.update(zoom_p=(i - 162) / 18)
        if i >= 168: kw.update(white_p=(i - 168) / 12)
        emit(im, f"{FR}/intro_{i:04d}.jpg", **kw)
        if i % 30 == 0: print(f"intro {i}/{n}")

# ================= 结尾(162 帧 / 5.4s) =================
def render_outro():
    n = 162
    title, _, _ = text_layer("AI News Hub", SONG_BLACK, 122, INK)
    kick, _, _ = text_layer("你的 AI 资讯早刊", HIRA_W6, 52, INK_SOFT, tracking=10)
    # 下载按钮整层(投影 + 红底胶囊 + 白字)
    btn = Image.new("RGBA", (520 * S, 220 * S), (0, 0, 0, 0))
    sh = Image.new("RGBA", btn.size, (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle([20 * S, 24 * S, 500 * S, 200 * S],
                                         radius=75 * S, fill=(20, 16, 12, 70))
    btn = Image.alpha_composite(btn, sh.filter(ImageFilter.GaussianBlur(10 * S)))
    db = ImageDraw.Draw(btn)
    db.rounded_rectangle([20 * S, 20 * S, 500 * S, 170 * S], radius=75 * S, fill=RED)
    fb = F(HIRA_W6, 64)
    bw = db.textlength("即刻下载", font=fb)
    db.text((20 * S + (480 * S - bw) / 2, 47 * S), "即刻下载", font=fb, fill=(255, 248, 244))
    l1, _, _ = text_layer("8 源聚合 · AI 摘要 · 中英双语", HIRA_W3, 42, INK_90, tracking=6)
    l2, _, _ = text_layer("GitHub Releases · github.com/burgessjp/AI-News-Hub",
                          HIRA_W3, 38, INK_90, tracking=4)

    for i in range(n):
        im = plain()
        d = ImageDraw.Draw(im)
        border(d, clamp01(i / 15))
        paste_faded(im, title, (150, 330), ease_out_cubic((i - 6) / 10), dy=20)
        kicker_rule(d, 150, 520, 560, ease_out_cubic((i - 12) / 10))
        paste_faded(im, kick, (150, 590), ease_out_cubic((i - 18) / 10), dx=-24)
        # 雷达弧逐圈画出 + 中心红点两连闪
        draw_radar(d, 1520, 540, 150, clamp01((i - 18) / 34))
        if i >= 54 and (i < 60 or 66 <= i < 72):
            r = 11 * S
            d.ellipse([1520 * S - r, 540 * S - r, 1520 * S + r, 540 * S + r], fill=RED)
        paste_faded(im, btn, (130, 680), ease_out_cubic((i - 60) / 14), dy=20)
        paste_faded(im, l1, (150, 926), ease_out_cubic((i - 84) / 10), dy=14)
        paste_faded(im, l2, (150, 982), ease_out_cubic((i - 90) / 10), dy=14)
        kw = dict(zoom_p=i / (n - 1), zoom_amt=0.025)
        if i < 12: kw.update(white_p=1 - i / 12)
        if i >= 138: kw.update(fade_p=(i - 138) / 24, fade_amt=0.72)
        emit(im, f"{FR}/outro_{i:04d}.jpg", **kw)
        if i % 30 == 0: print(f"outro {i}/{n}")

# ================= 版面段(6 段,段首文字滑入) =================
_BG = None
def bg_base():
    """静态底:纸面 + 完整双框 + 手机槽(投影 + 描边)。"""
    global _BG
    if _BG is None:
        im = plain()
        border(ImageDraw.Draw(im), 1.0)
        im = rounded_shadow(im, (1234 * S, 60 * S, 1666 * S, 1020 * S),
                            radius=18 * S, blur=22 * S, alpha=80, offset=(0, 10 * S))
        ImageDraw.Draw(im).rounded_rectangle(
            [1229 * S, 55 * S, 1670 * S, 1024 * S], radius=20 * S,
            outline=INK_70, width=3 * S)
        _BG = im
    return _BG.copy()

def icon_layer():
    """launch 段图标层(投影 + 圆角蒙版),2x。"""
    icon = Image.open(os.path.join(ROOT, ICON)).convert("RGBA").resize((210 * S, 210 * S), Image.LANCZOS)
    lay = Image.new("RGBA", (280 * S, 300 * S), (0, 0, 0, 0))
    lay = rounded_shadow(lay, (20 * S, 26 * S, 250 * S, 260 * S), radius=50 * S)
    mask = Image.new("L", (210 * S, 210 * S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, 210 * S - 1, 210 * S - 1],
                                           radius=50 * S, fill=255)
    lay.paste(icon, (20 * S, 20 * S), mask)
    return lay

def render_bg_seg(seg, dur):
    n = round(dur * FPS)
    static_path = f"{FR}/bgm_{seg}_{STILL_F:04d}.jpg"
    if seg == "launch":
        icon = icon_layer()
        brand, _, _ = text_layer("AI News Hub", SONG_BLACK, 104, INK)
        sub, _, _ = text_layer("你的 AI 资讯早刊", HIRA_W6, 52, INK_SOFT)
        tag, _, _ = text_layer("每天三分钟 看懂 AI 世界", HIRA_W6, 46, INK_SOFT, tracking=8)
        lines = [("L", icon, (112, 278), 0), ("L", brand, (398, 318), 2),
                 ("L", sub, (402, 452), 4), ("R", (398, 560, 520), None, 6),
                 ("L", tag, (398, 640), 8)]
    else:
        cfg = {"today":  ("每日早刊", "全网要闻", "一屏速览", "8 源聚合 · 权重排序 · 要点摘要"),
               "weekly": ("每周一期", "AI 周刊", "一周精华", "总综述 + 主题板块 + 精选条目"),
               "trends": ("趋势风向", "热词榜 ×", "趋势词云", "近 14 天热词 · 点击词条即搜"),
               "read":   ("纯净阅读", "沉浸阅读", "一键收藏", "内置浏览器 · 阅读模式 · 稍后读"),
               "dark":   ("明暗双态", "日间报纸", "墨色夜读", "跟随系统 · 自动切换")}[seg]
        kick, _, _ = text_layer(cfg[0], HIRA_W6, 46, RED, tracking=18)
        h1, _, _ = text_layer(cfg[1], SONG_BLACK, 116, INK)
        h2, _, _ = text_layer(cfg[2], SONG_BLACK, 116, INK)
        sub, _, _ = text_layer(cfg[3], HIRA_W3, 44, INK_SOFT)
        bot, _, _ = text_layer("AI News Hub · 你的 AI 资讯早刊", HIRA_W3, 36, INK_90)
        lines = [("L", kick, (128, 208), 0), ("H", (128, 292, 560), None, 1),
                 ("L", h1, (128, 340), 3), ("L", h2, (128, 500), 5),
                 ("R", (128, 690, 560), None, 8), ("L", sub, (128, 760), 10),
                 ("L", bot, (128, 950), 12)]

    def draw(i):
        im = bg_base()
        d = ImageDraw.Draw(im)
        for kind, payload, pos, start in lines:
            p = ease_out_cubic((i - start) / ANIM_DUR)
            if p <= 0: continue
            if kind == "L":
                paste_faded(im, payload, pos, p, dx=-40)
            elif kind == "H":
                hline(d, payload[0], payload[1], payload[2], p)
            else:
                kicker_rule(d, payload[0], payload[1], payload[2], p)
        return im

    for i in range(n):
        path = f"{FR}/bgm_{seg}_{i:04d}.jpg"
        if i > STILL_F:
            shutil.copyfile(static_path, path)
            continue
        emit(draw(i), path)
        if i == STILL_F: print(f"bgm_{seg} static @ {STILL_F}/{n}")
    print(f"bgm_{seg} {n} frames")

# ---------- 入口 ----------
if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default="all",
                    help="intro | outro | bg | 段名(launch/today/weekly/trends/read/dark)")
    os.makedirs(FR, exist_ok=True)
    a = ap.parse_args().only
    if a in ("all", "intro"):  render_intro()
    if a in ("all", "outro"):  render_outro()
    if a == "bg":
        for seg, dur in BG_SEGS: render_bg_seg(seg, dur)
    elif a in dict(BG_SEGS):
        render_bg_seg(a, dict(BG_SEGS)[a])
    print("done:", a)
