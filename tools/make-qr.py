#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
生成公招助手的发布页二维码卡片。

设计对齐项目自身视觉（深色底 + 琥珀强调色）：
  · 深色卡片上嵌一块白色圆角方，QR 用深色模块画在白底上 —— 对比度最高，
    扫码最稳（白模块画在深底上虽然好看，但部分扫码器会失败）。
  · 下方标注版本号与直链，打印/投屏都看得清。

用法：
    python make-qr.py                     # 用默认发布页地址
    python make-qr.py --url <url> --out <png>
"""
import argparse
import os
import sys

import qrcode
from qrcode.constants import ERROR_CORRECT_M
from PIL import Image, ImageDraw, ImageFont

# ---- 项目色板（与 Ui.java 保持一致）----
VOID = (0x0A, 0x0D, 0x0F)
PANEL = (0x14, 0x18, 0x1B)
PANEL2 = (0x1A, 0x1F, 0x23)
LINE = (0x26, 0x2E, 0x34)
TEXT = (0xE8, 0xED, 0xF0)
TEXT2 = (0x9A, 0xA7, 0xAE)
TEXT3 = (0x5F, 0x6B, 0x72)
AMBER = (0xFF, 0xB0, 0x20)
INK = (0x10, 0x14, 0x17)   # QR 模块色（近黑，非纯黑更柔和）

# 默认指向 Cloudflare R2 上的 latest.apk：
# 实测国内直连 GitHub 约 17 KB/s（15MB 要下十几分钟），R2 约 275 KB/s，快 16 倍。
DEFAULT_URL = "https://pub-f89e4319d7ba400db492a2608dc872ae.r2.dev/latest.apk"


def find_font(size, bold=False):
    """找一个能显示中文的字体。"""
    cands = [
        r"C:\Windows\Fonts\msyhbd.ttc" if bold else r"C:\Windows\Fonts\msyh.ttc",
        r"C:\Windows\Fonts\msyh.ttc",
        r"C:\Windows\Fonts\simhei.ttf",
        "/System/Library/Fonts/PingFang.ttc",
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    ]
    for p in cands:
        if p and os.path.exists(p):
            try:
                return ImageFont.truetype(p, size)
            except Exception:
                continue
    return ImageFont.load_default()


def find_mono(size):
    cands = [
        r"C:\Windows\Fonts\consola.ttf",
        r"C:\Windows\Fonts\cour.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf",
    ]
    for p in cands:
        if os.path.exists(p):
            try:
                return ImageFont.truetype(p, size)
            except Exception:
                continue
    return ImageFont.load_default()


def make_qr(url, target_px=560, border=3):
    """
    生成二维码位图，**不做插值缩放**。

    ★ 为什么不能用 resize ★
    最初写成 box_size=10 生成再 LANCZOS 缩放到 560px，结果肉眼看着没问题，
    但 pyzbar / 多数手机扫码器都解不出来：非整数倍缩放会把模块边缘插值成
    灰色细带，二值化之后模块宽度不再是整齐的整数像素，采样点落在边界上就废了。

    正确做法是先 make(fit=True) 拿到模块数，再反算一个整数 box_size，
    让每个模块恰好占 N×N 个像素。这样输出尺寸略有浮动（可能 520 或 574），
    但一定能扫。尺寸浮动用居中粘贴吸收掉。
    """
    qr = qrcode.QRCode(
        version=None,
        error_correction=ERROR_CORRECT_M,
        box_size=10,
        border=border,
    )
    qr.add_data(url)
    qr.make(fit=True)
    modules = qr.modules_count  # 不含 border 的有效模块数

    # 反算整数 box_size：总边长 = (modules + 2*border) * box
    # 注意 box_size 必须在构造 QRCode 时就定好 —— qrcode 的 make_image()
    # 不接受 box_size 参数（会撞 BaseImage.__init__ 的重复实参）。
    total_units = modules + border * 2
    box = max(1, round(target_px / total_units))

    qr2 = qrcode.QRCode(
        version=qr.version,
        error_correction=ERROR_CORRECT_M,
        box_size=box,
        border=border,
    )
    qr2.add_data(url)
    qr2.make(fit=False)
    return qr2.make_image(fill_color=INK, back_color=(255, 255, 255)).convert("RGB")


def rounded_card(size, radius, fill, outline=None, width=1):
    """生成一张圆角矩形蒙版图，用来把内容贴进圆角。"""
    w, h = size
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, w - 1, h - 1], radius=radius, fill=255)
    card = Image.new("RGB", (w, h), fill)
    if outline:
        d = ImageDraw.Draw(card)
        d.rounded_rectangle([0, 0, w - 1, h - 1], radius=radius, outline=outline, width=width)
    return card, mask


def build(url, out_path, version="v2.9.3"):
    W, H = 1080, 1440
    canvas = Image.new("RGB", (W, H), VOID)

    # 顶部一条琥珀细线，和面板标题上的 accent 呼应
    d0 = ImageDraw.Draw(canvas)
    d0.rectangle([0, 0, W, 6], fill=AMBER)

    # ---- 卡片 ----
    margin = 56
    card_w, card_h = W - margin * 2, H - margin * 2 - 40
    card, mask = rounded_card((card_w, card_h), 28, PANEL, outline=LINE, width=2)
    canvas.paste(card, (margin, margin + 20), mask)

    d = ImageDraw.Draw(canvas)
    cx = W // 2
    y = margin + 20 + 64

    # 小字距标题
    f_kicker = find_font(26)
    kicker = "ARKNIGHTS / RECRUIT"
    tw = d.textlength(kicker, font=f_kicker)
    d.text((cx - tw / 2, y), kicker, font=f_kicker, fill=AMBER)
    y += 52

    # 主标题
    f_title = find_font(70, bold=True)
    title = "公招助手"
    tw = d.textlength(title, font=f_title)
    d.text((cx - tw / 2, y), title, font=f_title, fill=TEXT)
    y += 96

    # 副标题
    f_sub = find_font(28)
    sub = "扫码在手机上打开下载页"
    tw = d.textlength(sub, font=f_sub)
    d.text((cx - tw / 2, y), sub, font=f_sub, fill=TEXT2)
    y += 60

    # ---- 二维码（白底深模块，扫码最稳）----
    # 注意：qr 的实际边长由整数 box_size 决定，会在 560 附近浮动几十像素，
    # 所以白卡尺寸按 qr 实测尺寸反推，而不是用写死的 qr_size。
    qr = make_qr(url, 560)
    qw, qh = qr.size
    pad = 26
    box_size = (qw + pad * 2, qh + pad * 2)
    qr_card, qr_mask = rounded_card(box_size, 24, (255, 255, 255))
    qx = cx - box_size[0] // 2
    canvas.paste(qr_card, (qx, y), qr_mask)
    canvas.paste(qr, (qx + pad, y + pad))
    y += box_size[1] + 48

    # ---- 版本徽章 ----
    f_badge = find_font(30, bold=True)
    label = f"最新版本  {version}"
    tw = d.textlength(label, font=f_badge)
    bw, bh = int(tw) + 56, 62
    bx = cx - bw // 2
    d.rounded_rectangle([bx, y, bx + bw, y + bh], radius=10,
                        fill=(0x1F, 0x1B, 0x12), outline=(0xB8, 0x78, 0x11), width=2)
    d.text((bx + 28, y + 12), label, font=f_badge, fill=AMBER)
    y += bh + 44

    # ---- 直链（等宽，可手动抄）----
    f_mono = find_mono(20)
    for line in short_url_lines(url):
        d.text((cx - d.textlength(line, font=f_mono) / 2, y), line, font=f_mono, fill=TEXT3)
        y += 34

    # ---- 底部提示 ----
    # 位置由卡片底边反推，避免像原来那样贴着边框（看起来像被切掉）
    f_note = find_font(24)
    note = "纯本地运行 · 不联网 · 不上传数据"
    card_bottom = margin + 20 + card_h
    d.text((cx - d.textlength(note, font=f_note) / 2, card_bottom - 68),
           note, font=f_note, fill=TEXT3)

    canvas.save(out_path, "PNG", optimize=True)
    return out_path


def short_url_lines(url):
    """把 URL 拆成适合在卡片上排版的 1~2 行（去掉协议头）。"""
    s = url.replace("https://", "").replace("http://", "")
    if len(s) <= 34:
        return [s]
    # 在第一个 / 之后断开
    i = s.find("/")
    if 0 < i < len(s) - 1:
        return [s[: i + 1], s[i + 1 :]]
    return [s[: len(s) // 2], s[len(s) // 2 :]]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default=DEFAULT_URL)
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "qr-release.png"))
    ap.add_argument("--version", default="v2.9.3")
    a = ap.parse_args()
    p = build(a.url, a.out, a.version)
    print("wrote", p, os.path.getsize(p), "bytes")
    print("url  ", a.url)


if __name__ == "__main__":
    sys.exit(main())
