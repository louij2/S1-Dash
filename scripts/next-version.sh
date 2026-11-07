#!/bin/sh
# Print the version the next release should use: one patch above the newest v* tag,
# or the default in app/build.gradle.kts if that's higher (to start a new minor).
set -e
cd "$(dirname "$0")/.."
floor=$(sed -n 's/.*S1DASH_VERSION") ?: "\(.*\)".*/\1/p' app/build.gradle.kts)
[ -n "$floor" ] || { echo "no default version in app/build.gradle.kts" >&2; exit 1; }
latest=$(git tag -l 'v[0-9]*.[0-9]*.[0-9]*' | sed 's/^v//' | sort -V | tail -1)
if [ -z "$latest" ] || [ "$(printf '%s\n%s\n' "$latest" "$floor" | sort -V | tail -1)" != "$latest" ]; then
  echo "$floor"
else
  echo "$latest" | awk -F. '{ print $1 "." $2 "." $3 + 1 }'
fi
