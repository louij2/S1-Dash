#!/bin/sh
# Build a signed release APK and publish it as a GitHub release. CI runs this on
# every merge that changes the app (.github/workflows/release.yml); by hand:
#
#   S1DASH_KEYSTORE=/path/s1dash-release.keystore \
#   S1DASH_KEYSTORE_PASSWORD=... \
#   GITHUB_TOKEN=... scripts/release.sh
#
# The version comes from scripts/next-version.sh and the tag is created with the
# release. The keystore must never be committed: anyone holding it can publish an
# APK that installs over every user's copy.
set -e
cd "$(dirname "$0")/.."
: "${S1DASH_KEYSTORE:?set S1DASH_KEYSTORE}" "${S1DASH_KEYSTORE_PASSWORD:?set S1DASH_KEYSTORE_PASSWORD}" "${GITHUB_TOKEN:?set GITHUB_TOKEN}"
# A different key would stop updates installing over people's copies.
key_sha256=dc009d3d49f8e58cfb659da20c89e70ee69232ebf7fcfc43a1ff36c788aa4cd7
v=$(scripts/next-version.sh)
sha=$(git rev-parse HEAD)
S1DASH_VERSION=$v ./gradlew assembleRelease --console=plain -q
apk="build/S1-Dash-$v.apk"
mkdir -p build && cp app/build/outputs/apk/release/app-release.apk "$apk"
apksigner=$(ls -d "$ANDROID_HOME"/build-tools/*/apksigner | tail -1)
"$apksigner" verify --print-certs "$apk" | grep -q "certificate SHA-256 digest: $key_sha256" || { echo "$apk isn't signed with the release key" >&2; exit 1; }
[ -n "$S1DASH_DRY_RUN" ] && { echo "built and checked $apk (dry run, nothing published)"; exit 0; }
repo=louij2/S1-Dash
api() { curl -sSf -H "Authorization: token $GITHUB_TOKEN" -H "Accept: application/vnd.github+json" "$@"; }
# A draft first: the release only appears, and the tag only exists, once the APK is on it.
rid=$(api -X POST "https://api.github.com/repos/$repo/releases" \
  -d "{\"tag_name\":\"v$v\",\"target_commitish\":\"$sha\",\"name\":\"S1 Dash $v\",\"draft\":true,\"generate_release_notes\":true}" | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")
api -H "Content-Type: application/vnd.android.package-archive" --data-binary @"$apk" \
  "https://uploads.github.com/repos/$repo/releases/$rid/assets?name=$(basename "$apk")" >/dev/null
api -X PATCH "https://api.github.com/repos/$repo/releases/$rid" -d '{"draft":false}' >/dev/null
echo "published $(basename "$apk") as v$v"
