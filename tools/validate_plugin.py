"""本地预检：模拟 JetBrains Marketplace 对 plugin.xml 的硬性校验。

用法： python tools/validate_plugin.py build/dist/loji-notes/lib/loji-notes.jar
"""

import re
import sys
import zipfile
import xml.etree.ElementTree as ET

# Marketplace 允许的 name 字符集：letters, digits, spaces, and .,+_-/:()#'&[]
NAME_OK = re.compile(r"^[A-Za-z0-9 .,+_\-/:()#'&\[\]]*$")


def main(path):
    jar = zipfile.ZipFile(path)
    raw = jar.read("META-INF/plugin.xml").decode("utf-8")
    root = ET.fromstring(raw)

    name = (root.findtext("name") or "").strip()
    version = (root.findtext("version") or "").strip()
    desc = root.findtext("description") or ""
    notes = root.findtext("change-notes") or ""

    def plain(s):
        return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", s)).strip()

    desc_text = plain(desc)
    notes_text = plain(notes)

    checks = [
        ("name 字符合法", bool(NAME_OK.match(name)), repr(name)),
        ("name 长度 2..100", 2 <= len(name) <= 100, str(len(name))),
        ("version 存在且是数字段", bool(re.match(r"^[0-9]+(\.[0-9]+)*$", version)), repr(version)),
        ("description 以拉丁字符开头", bool(re.match(r"^[A-Za-z0-9]", desc_text)), repr(desc_text[:40])),
        ("description 长度 >= 40", len(desc_text) >= 40, str(len(desc_text))),
        ("change-notes 以拉丁/数字开头", bool(re.match(r"^[A-Za-z0-9]", notes_text)), repr(notes_text[:40])),
        ("vendor 存在", bool((root.findtext("vendor") or "").strip()), root.findtext("vendor")),
        ("idea-version since-build 存在", root.find("idea-version") is not None
         and root.find("idea-version").get("since-build") is not None, ""),
    ]

    bad = 0
    for label, ok, detail in checks:
        print(("PASS  " if ok else "FAIL  ") + label + ("   " + detail if not ok else ""))
        if not ok:
            bad += 1
    print()
    print("== %d passed, %d failed ==" % (len(checks) - bad, bad))
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "build/dist/loji-notes/lib/loji-notes.jar")
