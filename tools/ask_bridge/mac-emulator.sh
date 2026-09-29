#!/bin/zsh
# Runs the 💬 ask bridge on a Mac for an Android emulator (or USB phone), so
# the feature can be tested without Termux.
#
#   tools/ask_bridge/mac-emulator.sh [serial]
#
# In the app: Settings → "Ask an LLM (💬)" → Choose folder → Download →
# moneo-ask → Use this folder. This script creates that folder on the device.
#
# moneo-ask.sh itself runs here, with two stand-ins for what macOS lacks:
# - stock bash is 3.2, which can't allocate fds with {var}>; the script is
#   patched to use fds 7/8 (it only ever has one worker open at a time);
# - there's no inotifywait, so a polling one goes first on PATH.
# A loop mirrors the device folder to a local one over adb: requests are
# pulled into inbox/, answers pushed back to outbox/. study-words.md is
# pulled too, but not synced to the Google Doc (study-sync.sh isn't run).
#
#   MONEO_ASK_DIR    local ask folder (default ~/.cache/moneo-ask-emulator)
#   MONEO_ASK_MODEL  passed through to moneo-ask.sh
set -eu
here=${0:A:h}
ADB=(~/Library/Android/sdk/platform-tools/adb ${1:+-s $1})
REMOTE=/sdcard/Download/moneo-ask
export MONEO_ASK_DIR=${MONEO_ASK_DIR:-$HOME/.cache/moneo-ask-emulator}
RUN=$(mktemp -d "${TMPDIR:-/tmp}/moneo-ask-mac.XXXXXX")
mkdir -p $MONEO_ASK_DIR/{inbox,outbox,done} $RUN/bin
$ADB shell mkdir -p $REMOTE/inbox $REMOTE/outbox

# moneo-ask.sh with fixed fds; fail loudly if its spawn/retire lines changed.
sed -e 's/exec {win}>"\$d.in" {rout}<"\$d.out"/win=7 rout=8; exec 7>"$d.in" 8<"$d.out"/' \
    -e 's/exec {win}>&- {rout}<&-/exec 7>\&- 8<\&-/' \
    $here/moneo-ask.sh > $RUN/moneo-ask.sh
if [[ $(grep -c 'exec 7' $RUN/moneo-ask.sh) != 2 ]]; then
  echo "moneo-ask.sh changed; update the bash 3.2 patch in ${0:t}" >&2
  exit 1
fi

# Polling stand-in for `inotifywait -m ... --format '%w%f' DIR/inbox DIR`:
# prints each file that appears or changes in the watched folders.
cat > $RUN/bin/inotifywait <<'EOF'
#!/bin/zsh
dirs=(); for a in "$@"; do [[ -d $a ]] && dirs+=(${a%/}); done
typeset -A seen
while :; do
  for d in $dirs; do
    for f in $d/*(N.); do
      m=$(stat -f %m $f)
      [[ ${seen[$f]-} == $m ]] || { seen[$f]=$m; print -r -- $f; }
    done
  done
  sleep 0.5
done
EOF
chmod +x $RUN/bin/inotifywait

# Device ↔ local mirror. A request's JSON is pulled only once it parses
# (the app creates the file before writing it), after its screenshot.
mirror() {
  local L=$MONEO_ASK_DIR f png b
  typeset -A pulled
  while :; do
    for f in $($ADB shell ls $REMOTE/inbox 2>/dev/null | tr -d '\r'); do
      [[ $f == *.json && -z ${pulled[$f]-} ]] || continue
      $ADB pull $REMOTE/inbox/$f $RUN/$f >/dev/null 2>&1 || continue
      jq -e . $RUN/$f >/dev/null 2>&1 || continue
      png=$(jq -r '.screenshot // empty' $RUN/$f)
      [[ -n $png ]] && $ADB pull $REMOTE/inbox/$png $L/inbox/$png >/dev/null 2>&1
      mv $RUN/$f $L/inbox/$f
      pulled[$f]=1
    done
    for f in $L/outbox/*.md(N); do
      b=${f:t}
      $ADB push $f $REMOTE/outbox/$b >/dev/null || continue
      $ADB shell rm -f $REMOTE/inbox/${b%.md}.json $REMOTE/inbox/${b%.md}.png
      rm $f
    done
    if $ADB pull $REMOTE/study-words.md $RUN/study-words.md >/dev/null 2>&1 &&
       ! cmp -s $RUN/study-words.md $L/study-words.md; then
      cp $RUN/study-words.md $L/study-words.md
    fi
    sleep 1
  done
}

mirror &
MIRROR=$!
trap 'kill $MIRROR 2>/dev/null; rm -rf $RUN' EXIT INT TERM
echo "device $REMOTE ↔ $MONEO_ASK_DIR"
PATH=$RUN/bin:$PATH /bin/bash $RUN/moneo-ask.sh
