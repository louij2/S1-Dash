#!/bin/sh
# Render the mockup screens to 800x480 PNGs with headless Chromium.
# Usage: docs/mockup/render.sh [path-to-headless-chrome]
set -e
here=$(cd "$(dirname "$0")" && pwd)
chrome=${1:-$(ls -d "$HOME"/.cache/ms-playwright/chromium_headless_shell-*/chrome-linux/headless_shell 2>/dev/null | tail -1)}
shot() { # shot <out.png> <query>
  "$chrome" --no-sandbox --hide-scrollbars --disable-gpu \
    --window-size=800,480 --virtual-time-budget=4000 \
    --screenshot="$here/$1" "file://$here/index.html?$2" >/dev/null 2>&1
  echo "$1"
}
shot screen-1-s1.png      "s=1&theme=s1"
shot screen-1-quattro.png "s=1&theme=quattro"
shot screen-1-mmi.png     "s=1&theme=mmi"
shot screen-2.png "s=2"
shot screen-3.png "s=3"
shot screen-4.png "s=4"
shot screen-5.png "s=5"
