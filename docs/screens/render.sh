#!/bin/sh
# Render the README screenshots from the real UI and its browser demo data.
# Usage: docs/screens/render.sh [path-to-headless-chrome]
set -e
here=$(cd "$(dirname "$0")" && pwd)
ui="file://$here/../../app/src/main/assets/ui/index.html"
chrome=${1:-$(ls -d "$HOME"/.cache/ms-playwright/chromium_headless_shell-*/chrome-linux/headless_shell 2>/dev/null | tail -1)}
[ -x "$chrome" ] || { echo "headless Chrome not found" >&2; exit 1; }
shot() { # shot <out.png> <width,height> <query>
  rm -f "$here/$1"
  "$chrome" --no-sandbox --hide-scrollbars --disable-gpu \
    --window-size="$2" --virtual-time-budget=4000 \
    --screenshot="$here/$1" "$ui?still&$3" >/dev/null 2>&1
  [ -s "$here/$1" ] || { echo "$1 didn't render" >&2; exit 1; }
  echo "$1"
}
shot perf-800x480.png   800,480   "screen=perf"
shot perf-1920x720.png  1920,720  "screen=perf"
shot perf-portrait.png  1080,1920 "screen=perf&theme=quattro"
shot perf-clutch.png    800,480   "screen=perf&slip"
shot engine.png         1024,600  "screen=engine"
shot awd.png            800,480   "screen=awd"
shot faults.png         800,480   "screen=faults"
shot settings.png       800,480   "screen=settings"
shot ecu-files.png      800,480   "screen=tune"
shot flash-checks.png   800,480   "screen=tune&flash=check"
shot flash-handoff.png  800,480   "screen=tune&flash=handoff"
shot flash-health.png   800,480   "screen=tune&flash=verify"
