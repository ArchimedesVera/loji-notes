"""打包脚本：把编译产物打成可安装的 IDEA 插件 zip。

只依赖标准库，避免联网装包。
用法：
  python tools/package.py --classes build/classes --out-jar build/dist/loji-notes/lib/loji-notes.jar \
      --plugin-dir build/dist/loji-notes --zip build/dist/loji-notes-0.1.0.zip [--stdlib path/to/kotlin-stdlib.jar]
"""

import argparse
import os
import zipfile


def jar_dir(src_dir, out_jar):
    out_jar = os.path.abspath(out_jar)
    os.makedirs(os.path.dirname(out_jar), exist_ok=True)
    count = 0
    with zipfile.ZipFile(out_jar, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for root, dirs, files in os.walk(src_dir):
            dirs.sort()
            for name in sorted(files):
                full = os.path.join(root, name)
                rel = os.path.relpath(full, src_dir).replace(os.sep, "/")
                zf.write(full, rel)
                count += 1
    return count


def zip_plugin(plugin_dir, out_zip):
    out_zip = os.path.abspath(out_zip)
    os.makedirs(os.path.dirname(out_zip), exist_ok=True)
    root_name = os.path.basename(plugin_dir.rstrip(os.sep))
    with zipfile.ZipFile(out_zip, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for root, dirs, files in os.walk(plugin_dir):
            dirs.sort()
            for name in sorted(files):
                full = os.path.join(root, name)
                rel = os.path.relpath(full, plugin_dir).replace(os.sep, "/")
                zf.write(full, root_name + "/" + rel)
    return out_zip


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--classes", required=True)
    ap.add_argument("--out-jar", required=True)
    ap.add_argument("--plugin-dir", required=True)
    ap.add_argument("--zip", required=True)
    ap.add_argument("--stdlib", default="")
    args = ap.parse_args()

    n = jar_dir(args.classes, args.out_jar)
    print("jar  : %s (%d entries)" % (args.out_jar, n))

    lib_dir = os.path.join(args.plugin_dir, "lib")
    os.makedirs(lib_dir, exist_ok=True)
    if args.stdlib:
        import shutil
        shutil.copyfile(args.stdlib, os.path.join(lib_dir, os.path.basename(args.stdlib)))
        print("stdlib: %s" % os.path.basename(args.stdlib))

    out = zip_plugin(args.plugin_dir, args.zip)
    print("zip  : %s (%.1f KB)" % (out, os.path.getsize(out) / 1024.0))


if __name__ == "__main__":
    main()
