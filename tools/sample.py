"""生成 sample 演示项目的注释数据（.loji/notes/...）。

用法： python tools/sample.py
"""

import json
import os
import re

ROOT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "sample")
REL = "src/main/java/com/demo/pay/SettlementService.java"

WS = re.compile(r"\s+")


def normalize(line):
    return WS.sub(" ", line.strip()).lower()


def fingerprint(line):
    h = 0xCBF29CE484222325  # FNV-1a 64 offset basis
    for b in normalize(line).encode("utf-8"):
        h ^= b
        h = (h * 0x100000001B3) & 0xFFFFFFFFFFFFFFFF
    return format(h, "x")


def notes_for(lines):
    return [
        {
            "id": "n_stock0001",
            "line": 11,
            "fingerprint": fingerprint(lines[11]),
            "ctxBefore": [lines[9], lines[10]],
            "ctxAfter": [lines[12], lines[13]],
            "text": "锁库存和扣款不是原子操作。charge 抛异常时库存会一直挂着。→ 改成先预占，失败再回滚。",
            "color": "amber",
            "dx": 40,
            "dy": 10,
            "collapsed": False,
            "created": 1755000000000,
            "updated": 1755000000000,
            "author": "loji",
        },
        {
            "id": "n_outbox001",
            "line": 13,
            "fingerprint": fingerprint(lines[13]),
            "ctxBefore": [lines[11], lines[12]],
            "ctxAfter": [lines[14], lines[15]],
            "text": "结算不该同步发消息：落 outbox 表，再由异步任务投递。",
            "color": "green",
            "dx": 40,
            "dy": 110,
            "collapsed": False,
            "created": 1755000000000,
            "updated": 1755000000000,
            "author": "loji",
        },
        {
            "id": "n_check0001",
            "line": 9,
            "fingerprint": fingerprint(lines[9]),
            "ctxBefore": [lines[7], lines[8]],
            "ctxAfter": [lines[10], lines[11]],
            "text": "参数校验统一用 Preconditions.checkNotNull。",
            "color": "blue",
            "dx": 40,
            "dy": -90,
            "collapsed": True,
            "created": 1755000000000,
            "updated": 1755000000000,
            "author": "loji",
        },
    ]


def main():
    src = os.path.join(ROOT, REL)
    with open(src, encoding="utf-8") as f:
        lines = f.read().split("\n")
    doc = {
        "format": "loji-notes",
        "version": 1,
        "file": REL,
        "notes": notes_for(lines),
    }
    out = os.path.join(ROOT, ".loji", "notes", REL + ".json")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        json.dump(doc, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print("wrote:", out)
    print("anchors:", [(n["line"] + 1, n["color"], n["fingerprint"]) for n in doc["notes"]])


if __name__ == "__main__":
    main()
