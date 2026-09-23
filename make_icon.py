# -*- coding: utf-8 -*-
"""生成「极简收音机」App 图标：深色渐变圆角底 + 橙色收音机 + 声波弧线。

坐标一律用 512 逻辑体系，实际画在 2048（x4）再缩回，得到抗锯齿。
主色：橙 #FF7A45 / 深底 #1A222B / 亮底 #26313D。
"""
import math
import os
from PIL import Image, ImageDraw

ORANGE = (255, 122, 69, 255)
DARK_TOP = (38, 49, 61, 255)      # #26313D
DARK_BOTTOM = (11, 15, 19, 255)   # #0B0F13
WHITE = (255, 255, 255, 255)

LOGICAL = 512
S = 2048
SCALE = S / LOGICAL   # 4


def L(v):
    return int(v * SCALE)


def make_master():
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    r = L(30)   # 圆角半径（512 体系 30px ≈ 5.9%）

    # 1) 圆角方形底（垂直渐变 #26313D -> #0B0F13）
    base = Image.new("RGBA", (S, S), DARK_BOTTOM)
    top = Image.new("RGBA", (S, S), DARK_TOP)
    grad = Image.new("L", (1, S), 0)
    for y in range(S):
        grad.putpixel((0, y), int(255 * (1 - y / S)))
    grad = grad.resize((S, S))
    base = Image.composite(top, base, grad)
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=r, fill=255)
    img.paste(base, (0, 0), mask)
    d = ImageDraw.Draw(img)

    # 2) 天线（斜线，从机身顶伸向右上）
    d.line([L(256), L(232), L(330), L(120)], fill=(255, 170, 120, 220), width=L(7))
    d.ellipse([L(322), L(112), L(338), L(128)], fill=ORANGE)

    # 3) 声波弧线：3 道同心弧，圆心在收音机顶部中央，开口朝下
    cx, cy = L(256), L(300)
    for i, rad in enumerate((58, 92, 126)):
        rr = L(rad)
        w = L(7)
        alpha = 255 - i * 60
        d.arc([cx - rr, cy - rr, cx + rr, cy + rr],
              start=205, end=335, fill=(255, 122, 69, alpha), width=w)

    # 4) 收音机机身（横向圆角矩形，橙色）
    bx0, bx1, by0, by1 = L(122), L(390), L(300), L(390)
    d.rounded_rectangle([bx0, by0, bx1, by1], radius=L(22), fill=ORANGE)

    # 5) 扬声器格栅（左侧 3 条深色竖线）
    for i in range(3):
        x = L(150) + i * L(30)
        d.rounded_rectangle([x, L(322), x + L(10), L(368)],
                            radius=L(5), fill=DARK_BOTTOM)

    # 6) 调频旋钮（右侧大圆 + 刻度点 + 白指针）
    kx, ky, kr = L(322), L(345), L(30)
    d.ellipse([kx - kr, ky - kr, kx + kr, ky + kr], fill=DARK_BOTTOM)
    for a in range(0, 360, 45):
        aa = math.radians(a)
        px = kx + int(math.cos(aa) * (kr - L(8)))
        py = ky + int(math.sin(aa) * (kr - L(8)))
        pr = L(2.5)
        d.ellipse([px - pr, py - pr, px + pr, py + pr], fill=ORANGE)
    d.line([kx, ky, kx + int((kr - L(7)) * 0.7), ky - int((kr - L(7)) * 0.7)],
           fill=WHITE, width=L(4))
    d.ellipse([kx - L(5), ky - L(5), kx + L(5), ky + L(5)], fill=WHITE)

    return img.resize((512, 512), Image.LANCZOS)


def main():
    proj = os.path.dirname(os.path.abspath(__file__))
    out_root = os.path.join(proj, "res")
    # 512 主图不放 res/：res 根目录不是合法的资源限定符目录，aapt2 会静默丢弃它。
    # 留在 res 里只会让人误以为它是有效资源，所以存到 artwork/。
    art = os.path.join(proj, "artwork")
    os.makedirs(art, exist_ok=True)
    master = make_master()
    master.save(os.path.join(art, "ic_launcher_master.png"))
    print("artwork/ic_launcher_master.png 512 OK")
    sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for dpi, px in sizes.items():
        d = os.path.join(out_root, "mipmap-" + dpi)
        os.makedirs(d, exist_ok=True)
        master.resize((px, px), Image.LANCZOS).save(os.path.join(d, "ic_launcher.png"))
        print("mipmap-%s %dpx OK" % (dpi, px))


if __name__ == "__main__":
    main()
