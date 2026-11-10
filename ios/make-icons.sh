#!/bin/sh
# Render the app icon from the Android logo (app/src/main/res/drawable/ic_launcher.xml)
# with headless Chrome, so both apps keep one logo.
# Usage: ios/make-icons.sh [path-to-headless-chrome]
set -e
here=$(cd "$(dirname "$0")" && pwd)
out="$here/S1Dash/Assets.xcassets/AppIcon.appiconset"
chrome=${1:-$(ls -d "$HOME"/.cache/ms-playwright/chromium_headless_shell-*/chrome-linux/headless_shell 2>/dev/null | tail -1)}
[ -x "$chrome" ] || { echo "headless Chrome not found" >&2; exit 1; }
tmp=$(mktemp -d)
python3 - "$here/../app/src/main/res/drawable/ic_launcher.xml" "$tmp/icon.html" <<'PY'
import sys, xml.etree.ElementTree as E
A = "{http://schemas.android.com/apk/res/android}"
paths = []
for p in E.parse(sys.argv[1]).getroot():
    g = lambda k, d="": p.get(A + k, d)
    paths.append('<path d="%s" fill="%s" stroke="%s" stroke-width="%s" stroke-linecap="%s"/>' % (
        g("pathData"), "none" if g("fillColor", "#00000000").endswith("00000000") and len(g("fillColor", "#00000000")) == 9 else g("fillColor", "none"),
        g("strokeColor", "none"), g("strokeWidth", "0"), g("strokeLineCap", "butt")))
# iOS icons are square and opaque: the round badge sits on a dark tile the system rounds.
svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="-2 -2 112 112" width="100%%" height="100%%"><rect x="-2" y="-2" width="112" height="112" fill="#0b0b0c"/>%s</svg>' % "".join(paths)
open(sys.argv[2], "w").write('<!doctype html><html><body style="margin:0;background:#0b0b0c;overflow:hidden"><div style="width:100vw;height:100vh">%s</div></body></html>' % svg)
PY
for s in 16 32 64 128 256 512 1024; do
  "$chrome" --no-sandbox --hide-scrollbars --disable-gpu --window-size=$s,$s --screenshot="$out/icon-$s.png" "file://$tmp/icon.html" >/dev/null 2>&1
  [ -s "$out/icon-$s.png" ] || { echo "icon-$s.png didn't render" >&2; exit 1; }
done
rm -rf "$tmp"
echo "icons in $out"
