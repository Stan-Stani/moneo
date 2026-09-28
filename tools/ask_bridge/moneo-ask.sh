#!/usr/bin/env bash
# Answers the app's 💬 questions with Claude Code (see README.md).
#
# The app writes inbox/<id>.png then inbox/<id>.json into the ask folder;
# this runs `claude -p` on each request and writes outbox/<id>.md, which the
# app shows and deletes. Handled requests and replies are kept in done/.
#
#   MONEO_ASK_DIR    ask folder (default ~/moneo-ask)
#   MONEO_ASK_MODEL  model for claude --model (default: claude's own default)
set -u

DIR="${MONEO_ASK_DIR:-$HOME/moneo-ask}"
MODEL="${MONEO_ASK_MODEL:-}"
SESSION_FILE="$DIR/.session"

for cmd in claude jq inotifywait; do
  command -v "$cmd" >/dev/null || { echo "missing $cmd (Termux: pkg install jq inotify-tools)" >&2; exit 1; }
done
mkdir -p "$DIR/inbox" "$DIR/outbox" "$DIR/done"
command -v termux-wake-lock >/dev/null && termux-wake-lock

read -r -d '' SYSTEM_PROMPT <<'EOF'
You are a Korean reading tutor for someone playing Pokémon LeafGreen in
Korean (the 2024 fan translation) on their phone, while walking. They are an
English speaker learning Korean.

Each request is a JSON object describing the game screen:
- message: the text of the open message box, decoded from game memory (exact;
  null when no box is open, e.g. menus or the overworld)
- words: dictionary words in that message, with an English gloss and whether
  the player already knows each one
- knownWords: every Korean word the player has learned in their flashcards
- question: what they want to know
- screenshot: a screenshot of the game (240x160, enlarged). Read it only when
  message is null or the question is about something on screen.
- followUp: true when they are asking again about the same screen

Answer the question. By default, restate the line in simpler Korean, built
from knownWords where you can, then briefly explain in English the grammar
endings or idioms that make it hard. Your answer is shown in a small panel
beside the game: plain text, no markdown headings or tables, and under about
100 words unless they ask for more. Do not spoil anything that happens later
in the game.
EOF

handle() {
  local json="$1" id out reply sid shot
  id="$(basename "$json" .json)"
  [[ -f "$json" ]] || return 0
  # The app creates the file before writing it; wait for the full JSON.
  jq -e . "$json" >/dev/null 2>&1 || return 0
  shot="$(jq -r '.screenshot // empty' "$json")"

  local prompt="Request (inbox/$id.json):
$(cat "$json")"
  [[ -n "$shot" ]] && prompt+="

Screenshot: inbox/$shot"

  local args=(-p "$prompt" --output-format json --append-system-prompt "$SYSTEM_PROMPT" --allowedTools Read)
  [[ -n "$MODEL" ]] && args+=(--model "$MODEL")
  if [[ "$(jq -r .followUp "$json")" == true && -s "$SESSION_FILE" ]]; then
    args+=(--resume "$(cat "$SESSION_FILE")")
  fi

  echo "[$(date +%T)] $id: $(jq -r .question "$json")"
  # </dev/null: otherwise claude reads the inotifywait pipe and blocks on queued events.
  out="$(cd "$DIR" && claude "${args[@]}" 2>"$DIR/.last_err" </dev/null)"
  reply="$(jq -r '.result // empty' <<<"$out" 2>/dev/null)"
  sid="$(jq -r '.session_id // empty' <<<"$out" 2>/dev/null)"
  echo "    ($(jq -r '[(.modelUsage // {} | keys | join("+")), "\((.duration_ms // 0) / 1000 | floor)s"] | join(", ")' <<<"$out" 2>/dev/null))"
  [[ -n "$sid" ]] && echo "$sid" >"$SESSION_FILE"
  [[ -z "$reply" ]] && reply="⚠ claude failed: $(tail -c 300 "$DIR/.last_err")"
  echo "$reply" | sed 's/^/    /'

  # Rename into place so the app never reads a half-written reply.
  printf '%s\n' "$reply" >"$DIR/outbox/$id.md.part"
  cp "$DIR/outbox/$id.md.part" "$DIR/done/$id.md"
  mv "$DIR/outbox/$id.md.part" "$DIR/outbox/$id.md"
  mv "$json" "$DIR/done/"
  [[ -n "$shot" && -f "$DIR/inbox/$shot" ]] && mv "$DIR/inbox/$shot" "$DIR/done/"
  return 0
}

echo "watching $DIR/inbox"
for f in "$DIR"/inbox/*.json; do [[ -e "$f" ]] && handle "$f"; done
inotifywait -m -q -e close_write -e moved_to --format '%f' "$DIR/inbox" | while read -r name; do
  [[ "$name" == *.json ]] && handle "$DIR/inbox/$name"
done
