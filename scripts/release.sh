#!/bin/sh
# Build a signed release APK and attach it to a GitHub release.
#
#   S1DASH_KEYSTORE=/path/s1dash-release.keystore \
#   S1DASH_KEYSTORE_PASSWORD=... \
#   GITHUB_TOKEN=... scripts/release.sh
#
# The tag is v<versionName> from app/build.gradle.kts; tag and push it first.
# The keystore must never be committed: anyone holding it can publish an APK
# that installs over every user's copy.
set -e
cd "$(dirname "$0")/.."
: "${S1DASH_KEYSTORE:?set S1DASH_KEYSTORE}" "${S1DASH_KEYSTORE_PASSWORD:?set S1DASH_KEYSTORE_PASSWORD}" "${GITHUB_TOKEN:?set GITHUB_TOKEN}"
v=$(grep versionName app/build.gradle.kts | sed 's/.*"\(.*\)".*/\1/')
./gradlew assembleRelease --console=plain -q
apk="build/S1-Dash-$v.apk"
mkdir -p build && cp app/build/outputs/apk/release/app-release.apk "$apk"
repo=louij2/S1-Dash
api() { curl -sf -H "Authorization: token $GITHUB_TOKEN" -H "Accept: application/vnd.github+json" "$@"; }
rid=$(api "https://api.github.com/repos/$repo/releases/tags/v$v" | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])" 2>/dev/null || true)
if [ -z "$rid" ]; then
  rid=$(api -X POST "https://api.github.com/repos/$repo/releases" \
    -d "{\"tag_name\":\"v$v\",\"name\":\"S1 Dash $v\",\"body\":\"See INSTALL in the README.\"}" | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")
fi
api -H "Content-Type: application/vnd.android.package-archive" --data-binary @"$apk" \
  "https://uploads.github.com/repos/$repo/releases/$rid/assets?name=$(basename "$apk")" >/dev/null
echo "uploaded $(basename "$apk") to v$v"
