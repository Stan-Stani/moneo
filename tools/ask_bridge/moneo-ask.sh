#!/usr/bin/env bash
# Answers the app's 💬 questions with Claude Code (see README.md).
#
# The app writes inbox/<id>.png then inbox/<id>.json into the ask folder;
# this asks claude about each request and writes outbox/<id>.md, which the
# app shows and deletes. Handled requests and replies are kept in done/.
#
# claude runs as one long-lived worker (-p with stream-json input), since
# starting it costs ~30 s under a Termux proot. A follow-up question continues
# its conversation; a new question is preceded by /clear, which starts a fresh
# one in the same process.
#
# When the app rewrites study-words.md at the top of the folder, study-sync.sh
# (next to this script) copies it into a Google Doc, in the background.
#
#   MONEO_ASK_DIR    ask folder (default ~/moneo-ask)
#   MONEO_ASK_MODEL  model for claude --model (default: claude's own default)
set -u

DIR="${MONEO_ASK_DIR:-$HOME/moneo-ask}"
MODEL="${MONEO_ASK_MODEL:-}"
RUN="$(mktemp -d "${TMPDIR:-/tmp}/moneo-ask.XXXXXX")"
REPLY_TIMEOUT=300

for cmd in claude jq inotifywait; do
  command -v "$cmd" >/dev/null || { echo "missing $cmd (Termux: pkg install jq inotify-tools)" >&2; exit 1; }
done
mkdir -p "$DIR/inbox" "$DIR/outbox" "$DIR/done"
command -v termux-wake-lock >/dev/null && termux-wake-lock

# The app's direct-API mode (AskPrompt.kt) carries a copy of this prompt; keep them in step.
read -r -d '' SYSTEM_PROMPT <<'EOF'
You are a Korean reading tutor for someone playing Pokémon LeafGreen in
Korean (the 2024 fan translation) on their phone, while walking. They are an
English speaker learning Korean.

Each request is a JSON object describing the game screen:
- message: the line the question is about, decoded from game memory (exact):
  the open message box, or, when messageOnScreen is false, the last line shown,
  messageSecondsAgo seconds ago, which has closed since. null when there's
  neither (e.g. menus, or nothing said lately)
- recentMessages: the lines shown before message, oldest first, each with
  secondsAgo. Use them when the question is about something said earlier or
  about the conversation as a whole
- words: dictionary words in that message, with an English gloss and whether
  the player already knows each one
- knownWords: every Korean word the player has learned in their flashcards
- question: what they want to know
- screenshot: a screenshot of the game (240x160, enlarged). Read it only when
  message is null or the question is about something on screen.
- followUp: true when they are asking again about the same screen

Answer the question in Korean only: simple, short sentences built from
knownWords where you can. Explain hard words, grammar endings and idioms in
simpler Korean too, e.g. "~지 = 다들 아는 걸 말할 때 붙여요". Use English only
when the question explicitly asks for it (e.g. "in English", "translate");
a question merely written in English still gets a Korean answer. The
English glosses in words are for you; don't quote them in a Korean answer. By default, restate the line in easier Korean and then
explain the one or two hardest parts. Your answer is shown in a small panel
beside the game: plain text, no markdown headings or tables, and under about
100 words unless they ask for more. Do not spoil anything that happens later
in the game.

End every answer with one last line in exactly this form, which the app
hides and turns into study buttons:
[[words: 간판, 도움이 되다]]
listing the dictionary forms (되다, not 되지) of up to 6 Korean words from
the line or your answer that are worth learning and not in knownWords.
Write [[words: ]] when there are none.
EOF

CLAUDE_ARGS=(-p --input-format stream-json --output-format stream-json --verbose
  --append-system-prompt "$SYSTEM_PROMPT" --allowedTools Read)
[[ -n "$MODEL" ]] && CLAUDE_ARGS+=(--model "$MODEL")

# The worker is "pid in-fd out-fd dir": claude reading JSON lines from a
# FIFO and writing stream-json events to another. ASKED counts questions in
# its current conversation.
CUR=""
ASKED=0
SEQ=0

# Starts a worker into variable $1. Not in $(...): the FIFO fds must stay
# open in this shell.
spawn() {
  local d="$RUN/w$((++SEQ))" win rout
  mkfifo "$d.in" "$d.out"
  (cd "$DIR" && exec claude "${CLAUDE_ARGS[@]}" <"$d.in" >"$d.out" 2>"$d.err") &
  local pid=$!
  exec {win}>"$d.in" {rout}<"$d.out"
  printf -v "$1" '%s' "$pid $win $rout $d"
}

alive() { [[ -n "$1" ]] && kill -0 "${1%% *}" 2>/dev/null; }

retire() {
  [[ -n "$1" ]] || return 0
  local pid win rout d
  read -r pid win rout d <<<"$1"
  exec {win}>&- {rout}<&-
  kill "$pid" 2>/dev/null
  rm -f "$d.in" "$d.out" "$d.err"
}

cleanup() { retire "$CUR"; rm -rf "$RUN"; }
trap cleanup EXIT
trap 'exit 0' INT TERM
trap '' PIPE  # a dead worker must not take the watcher down with it

# Sends $2 to worker $1; sets ANSWER and META, or returns 1.
ask() {
  local pid win rout d line type
  read -r pid win rout d <<<"$1"
  jq -cn --arg t "$2" '{type: "user", message: {role: "user", content: $t}}' >&"$win" || return 1
  while IFS= read -r -t "$REPLY_TIMEOUT" -u "$rout" line; do
    type="$(jq -r '.type // empty' <<<"$line" 2>/dev/null)"
    [[ "$type" == result ]] || continue
    ANSWER="$(jq -r '.result // empty' <<<"$line")"
    META="$(jq -r '[(.modelUsage // {} | keys | join("+")), "\((.duration_ms // 0) / 1000 | floor)s"] | join(", ")' <<<"$line")"
    [[ -n "$ANSWER" ]] && return 0
    ANSWER="⚠ claude: $(jq -r '.subtype // "no answer"' <<<"$line")"
    return 0
  done
  return 1
}

handle() {
  local json="$1" id shot worker t0
  id="$(basename "$json" .json)"
  [[ -f "$json" ]] || return 0
  # The app creates the file before writing it; wait for the full JSON.
  jq -e . "$json" >/dev/null 2>&1 || return 0
  shot="$(jq -r '.screenshot // empty' "$json")"

  local prompt="Request (inbox/$id.json):
$(cat "$json")"
  [[ -n "$shot" ]] && prompt+="

Screenshot: inbox/$shot"

  echo "[$(date +%T)] $id: $(jq -r .question "$json")"
  if ! alive "$CUR"; then
    retire "$CUR"
    spawn CUR
    ASKED=0
  fi
  worker="$CUR"
  if [[ "$(jq -r .followUp "$json")" != true && $ASKED -gt 0 ]]; then
    ask "$worker" /clear || true
    ASKED=0
  fi

  t0=$SECONDS
  if ! ask "$worker" "$prompt"; then
    ANSWER="⚠ claude stopped answering: $(tail -c 300 "${worker##* }.err" 2>/dev/null)"
    META="failed"
    retire "$CUR"
    CUR=""
  fi
  ASKED=$((ASKED + 1))
  echo "    ($META; $((SECONDS - t0))s here)"
  echo "$ANSWER" | sed 's/^/    /'

  # Rename into place so the app never reads a half-written reply.
  printf '%s\n' "$ANSWER" >"$DIR/outbox/$id.md.part"
  cp "$DIR/outbox/$id.md.part" "$DIR/done/$id.md"
  mv "$DIR/outbox/$id.md.part" "$DIR/outbox/$id.md"
  mv "$json" "$DIR/done/"
  [[ -n "$shot" && -f "$DIR/inbox/$shot" ]] && mv "$DIR/inbox/$shot" "$DIR/done/"

  # Restart a worker that died now, while the player reads, not on the next question.
  alive "$CUR" || { retire "$CUR"; spawn CUR; ASKED=0; }
  return 0
}

spawn CUR
echo "watching $DIR/inbox"
for f in "$DIR"/inbox/*.json; do [[ -e "$f" ]] && handle "$f"; done
SYNC="$(dirname "$(readlink -f "$0")")/study-sync.sh"
sync_study_words() {
  [[ -x "$SYNC" ]] || return 0
  "$SYNC" "$DIR/study-words.md" 2>&1 | sed "s/^/[$(date +%T)] /" &
}

# Process substitution keeps the loop (and the worker) in this shell.
while read -r path; do
  case "$path" in
    "$DIR"/inbox/*.json) handle "$path" ;;
    "$DIR"/study-words.md) sync_study_words ;;
  esac
done < <(inotifywait -m -q -e close_write -e moved_to --format '%w%f' "$DIR/inbox" "$DIR")
