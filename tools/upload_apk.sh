#!/usr/bin/env bash
# Build the debug APK and replace the single "poketrek-debug.apk" on Google
# Drive in place (same file, same share link). Creates it if missing.
# Needs the gdrive CLI (v3) signed in to the Drive account.
set -euo pipefail
cd "$(dirname "$0")/.."
NAME=poketrek-debug.apk
APK=app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleDebug -q
id=$(gdrive files list --query "name = '$NAME' and trashed = false" --skip-header | awk 'NR==1{print $1}')
if [ -n "$id" ]; then
  gdrive files update "$id" "$APK" >/dev/null
  gdrive files rename "$id" "$NAME" >/dev/null   # update renames it to the local basename
else
  tmp=$(mktemp -d)/$NAME; cp "$APK" "$tmp"
  id=$(gdrive files upload "$tmp" | awk '/^Id:/{print $2}')
fi
echo "$NAME <- $(git rev-parse --short HEAD) ($(md5sum "$APK" 2>/dev/null | cut -d' ' -f1 || md5 -q "$APK"))"
echo "https://drive.google.com/file/d/$id/view"
