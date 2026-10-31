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

DEFAULT_URL = "https://github.com/TttMc/arknights-recruit-helper/releases/latest"


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


def make_qr(url, box_px=560):
    qr = qrcode.QRCode(
        version=None,
        error_correction=ERROR_CORRECT_M,
        box_size=10,
        border=2,
    )
    qr.add_data(url)
    qr.make(fit=True)
    img = qr.make_image(fill_color=INK, back_color=(255, 255, 255)).convert("RGB")
    return img.resize((box_px, box_px), Image.LANCZOS)


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
    qr_size = 560
    qr = make_qr(url, qr_size)
    pad = 26
    box_size = (qr_size + pad * 2, qr_size + pad * 2)
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
    f_mono = find_mono(22)
    d.text((cx - d.textlength("releases/latest", font=f_mono) / 2, y),
           "releases/latest", font=f_mono, fill=TEXT3)
    y += 40
    host = "github.com/TttMc/arknights-recruit-helper"
    d.text((cx - d.textlength(host, font=f_mono) / 2, y), host, font=f_mono, fill=TEXT3)

    # ---- 底部提示 ----
    # 位置由卡片底边反推，避免像原来那样贴着边框（看起来像被切掉）
    f_note = find_font(24)
    note = "纯本地运行 · 不联网 · 不上传数据"
    card_bottom = margin + 20 + card_h
    d.text((cx - d.textlength(note, font=f_note) / 2, card_bottom - 68),
           note, font=f_note, fill=TEXT3)

    canvas.save(out_path, "PNG", optimize=True)
    return out_path


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
