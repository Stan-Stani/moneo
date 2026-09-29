#!/usr/bin/env bash
# Copies the app's study-words.md into a Google Doc, replacing its content,
# so a Claude project that has the Doc in its knowledge sees the current
# words. The first run creates the Doc; its id is kept in ~/.moneo-ask.
#
#   study-sync.sh [file]   (default: $MONEO_ASK_DIR/study-words.md)
#
# Google access comes from an rclone Drive remote (MONEO_DRIVE_REMOTE,
# default "moneo"), best made with scope drive.file so it can only touch
# files it created:  rclone config create moneo drive scope=drive.file
# rclone keeps the token fresh; the upload itself is a plain Drive API
# call, since rclone can't replace a Google Doc's content in place.
set -euo pipefail

FILE="${1:-${MONEO_ASK_DIR:-$HOME/moneo-ask}/study-words.md}"
REMOTE="${MONEO_DRIVE_REMOTE:-moneo}"
TITLE="${MONEO_STUDY_DOC_TITLE:-Moneo study words}"
STATE="$HOME/.moneo-ask"
ID_FILE="$STATE/study-doc-id"
API=https://www.googleapis.com/upload/drive/v3/files
mkdir -p "$STATE"

[[ -s "$FILE" ]] || { echo "study-sync: no $FILE" >&2; exit 1; }

# Any call through the remote refreshes an expired token and saves it.
rclone lsf "$REMOTE:" --max-depth 1 >/dev/null
TOKEN="$(rclone config dump | jq -r --arg r "$REMOTE" '.[$r].token' | jq -r .access_token)"
[[ -n "$TOKEN" && "$TOKEN" != null ]] || { echo "study-sync: no token for remote $REMOTE" >&2; exit 1; }

create() {
  local boundary=moneo$RANDOM$RANDOM
  {
    printf -- '--%s\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n' "$boundary"
    jq -cn --arg t "$TITLE" '{name: $t, mimeType: "application/vnd.google-apps.document"}'
    printf -- '\r\n--%s\r\nContent-Type: text/markdown; charset=UTF-8\r\n\r\n' "$boundary"
    cat "$FILE"
    printf -- '\r\n--%s--\r\n' "$boundary"
  } | curl -sS --fail-with-body -X POST "$API?uploadType=multipart&fields=id" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: multipart/related; boundary=$boundary" \
        --data-binary @- | jq -r .id >"$ID_FILE"
  echo "study-sync: created Google Doc $(cat "$ID_FILE") (https://docs.google.com/document/d/$(cat "$ID_FILE"))"
}

if [[ ! -s "$ID_FILE" ]]; then
  create
  exit 0
fi

status="$(curl -sS -o /dev/null -w '%{http_code}' -X PATCH "$API/$(cat "$ID_FILE")?uploadType=media" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: text/markdown; charset=UTF-8" \
  --data-binary @"$FILE")"
case "$status" in
  200) echo "study-sync: updated $(cat "$ID_FILE")" ;;
  404) echo "study-sync: Doc gone, making a new one" >&2; rm -f "$ID_FILE"; create ;;
  *) echo "study-sync: Drive answered HTTP $status" >&2; exit 1 ;;
esac
