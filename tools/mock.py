"""生成效果预览图（按渲染规格画的示意图，不是运行时截图）。

用法： python tools/mock.py docs/preview.png
"""

import os
import re
import sys

from PIL import Image, ImageDraw, ImageFont

W, H = 1280, 780
BG = (30, 31, 34)
PANEL = (43, 45, 48)
TEXT = (188, 190, 196)

CONSOLA = r"C:\Windows\Fonts\consola.ttf"
MSYH = r"C:\Windows\Fonts\msyh.ttc"
CJK = re.compile(r"[\u4e00-\u9fff]")


def font(path, size):
    try:
        return ImageFont.truetype(path, size)
    except Exception:
        return ImageFont.load_default()


f_code = font(CONSOLA, 15)
f_cjk = font(MSYH, 14)
f_ui = font(MSYH, 13)
f_ui_s = font(MSYH, 12)
f_badge = font(CONSOLA, 11)

AMB = (242, 183, 51)
GRN = (76, 175, 125)
BLU = (74, 158, 255)

TREE_W = 250
GUTTER_W = 58
TOP = 66
LINE_H = 24
ED_X = TREE_W
CODE_X = ED_X + GUTTER_W + 12
RAIL_X = 900

CODE = [
    ("package com.demo.pay;", (204, 204, 204)),
    ("", TEXT),
    ("import java.util.List;", (204, 204, 204)),
    ("", TEXT),
    ("/** 结算主流程 */", (106, 135, 89)),
    ("public class SettlementService {", (255, 198, 109)),
    ("", TEXT),
    ("    Result settle(Order order) {", (255, 198, 109)),
    ("        if (order == null) {", (204, 120, 120)),
    ("            throw new IllegalArgumentException();", (169, 183, 198)),
    ("        }", (204, 204, 204)),
    ("        // 先锁库存，再扣款", (106, 135, 89)),
    ("        stock.lock(order);", (169, 183, 198)),
    ("        payment.charge(order);", (169, 183, 198)),
    ("        return Result.ok();", (169, 183, 198)),
    ("    }", (204, 204, 204)),
    ("}", (255, 198, 109)),
]


def mix(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def line_y(i):
    return TOP + i * LINE_H


def card_metrics(lines, collapsed):
    header = 26
    lh = 16 if collapsed else 17
    body = len(lines) * lh
    return header + body + (10 if collapsed else 14), header, lh


def draw_card(d, x, y, w, accent, lines, collapsed=False, hover=False, row=0):
    h, header, lh = card_metrics(lines, collapsed)
    fill = mix(BG, accent, 0.20)
    border = mix(BG, accent, 0.60)

    d.rounded_rectangle([x + 1, y + 3, x + w + 1, y + h + 3], radius=10, fill=(0, 0, 0))
    d.rounded_rectangle([x, y, x + w, y + h], radius=10, fill=fill, outline=border,
                        width=2 if hover else 1)
    d.rounded_rectangle([x, y + 2, x + 4, y + h - 2], radius=2, fill=accent)

    cy = y + header // 2 + 1
    if collapsed:
        d.polygon([(x + 14, cy - 4), (x + 14, cy + 4), (x + 20, cy)], fill=(150, 154, 160))
    else:
        d.polygon([(x + 12, cy - 3), (x + 20, cy - 3), (x + 16, cy + 3)], fill=(150, 154, 160))

    badge = "L" + str(row)
    d.text((x + w - 8 - f_badge.getlength(badge), y + 7), badge, font=f_badge, fill=(130, 134, 140))

    ty = y + header + (4 if collapsed else 6)
    for i, line in enumerate(lines):
        f = f_cjk if CJK.search(line) else f_code
        if collapsed:
            f = font(MSYH, 11) if CJK.search(line) else font(CONSOLA, 12)
        d.text((x + 14, ty + i * lh), line, font=f, fill=(214, 216, 220))
    return h


def leader(d, start, end, color, width=2, dashed=False):
    (x0, y0), (x1, y1) = start, end
    mx = (x0 + x1) / 2.0
    pts = []
    for i in range(49):
        t = i / 48.0
        x = (1 - t) ** 2 * x0 + 2 * (1 - t) * t * mx + t ** 2 * x1
        y = (1 - t) ** 2 * y0 + 2 * (1 - t) * t * ((y0 + y1) / 2.0) + t ** 2 * y1
        pts.append((x, y))
    for i in range(len(pts) - 1):
        if dashed and (i // 3) % 2 == 1:
            continue
        d.line([pts[i], pts[i + 1]], fill=color, width=width)
    d.ellipse([x1 - 3, y1 - 3, x1 + 3, y1 + 3], fill=color)


def text_end_x(code):
    f = f_cjk if CJK.search(code) else f_code
    return CODE_X + int(f.getlength(code))


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "docs/preview.png"
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)

    # 工具条
    d.rectangle([0, 0, W, 38], fill=(43, 45, 48))
    d.text((16, 12), "SettlementService.java", font=f_ui, fill=(223, 225, 230))
    d.text((232, 13), "com.demo.pay", font=f_ui_s, fill=(110, 114, 120))

    # 项目树
    d.rectangle([0, 38, TREE_W, H], fill=PANEL)
    tree = [
        (0, "demo", True, (223, 225, 230)),
        (1, "src / main / java", True, (188, 190, 196)),
        (2, "com.demo.pay", True, (188, 190, 196)),
        (3, "SettlementService", False, (108, 168, 255)),
        (3, "Order", False, (188, 190, 196)),
        (3, "Result", False, (188, 190, 196)),
        (1, ".loji / notes", True, (120, 124, 130)),
        (2, "SettlementService.java.json", False, (110, 160, 130)),
    ]
    y = 58
    for depth, name, folder, color in tree:
        x = 14 + depth * 14
        if folder:
            d.polygon([(x, y + 5), (x + 9, y + 5), (x + 4, y + 11)], fill=(150, 154, 160))
        d.text((x + 13, y - 1), name, font=f_ui_s, fill=color)
        y += 24

    # 锚点行底色
    d.rectangle([ED_X, line_y(11) - 3, W, line_y(11) + LINE_H - 3], fill=(36, 38, 42))
    d.rectangle([ED_X, line_y(11) - 3, ED_X + 3, line_y(11) + LINE_H - 3], fill=(120, 92, 30))

    # 代码
    for i, (code, color) in enumerate(CODE):
        ly = line_y(i)
        n = str(i + 1)
        d.text((ED_X + 34 - len(n) * 4, ly + 2), n, font=f_code, fill=(96, 100, 106))
        f = f_cjk if CJK.search(code) else f_code
        d.text((CODE_X, ly + 1), code, font=f, fill=color)

    # 三张卡片 + 指向线
    cards = [
        (AMB, 120, 300, ["锁库存和扣款不是原子操作。", "charge 抛异常时库存会一直挂着。", "", "→ 改成先预占，失败再回滚。"], False, 11),
        (GRN, 300, 300, ["结算不该同步发消息。", "落 outbox 表，再异步投递。"], False, 13),
        (BLU, 430, 244, ["参数校验统一用 Preconditions"], True, 8),
    ]
    for accent, y, w, lines, collapsed, anchor_line in cards:
        h, header, _ = card_metrics(lines, collapsed)
        draw_card(d, RAIL_X, y, w, accent, lines, collapsed, hover=(accent == GRN), row=anchor_line + 1)
        leader(d, (RAIL_X, y + header // 2 + 2),
               (text_end_x(CODE[anchor_line][0]) + 14, line_y(anchor_line) + 12),
               mix(BG, accent, 0.55))

    # 底部提示
    d.text((ED_X + 16, H - 28),
           "Ctrl+Alt+N 新建    ·    拖动卡片    ·    双击编辑    ·    右键换色 / 删除    ·    点小三角收起    ·    卡片位置与颜色存进 .loji/",
           font=f_ui_s, fill=(120, 124, 130))

    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
    img.save(out)
    print("saved:", out, img.size)


if __name__ == "__main__":
    main()
