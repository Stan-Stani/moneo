#!/usr/bin/env bash
# Start / stop / check the ask-bridge watcher in the background.
#
#   moneo-ask-start.sh [start|stop|status|log]   (default: start)
#
# start does nothing if the watcher is already running, so it is safe to call
# from ~/.bashrc or a Termux:Boot script. The watcher runs in its own session
# (setsid) so stop can kill it together with its inotifywait and any claude.
#
#   MONEO_ASK_DIR  ask folder (default: Termux's ~/moneo-ask when this runs
#                  in a proot that can see it, else ~/moneo-ask)
#   MONEO_ASK_MODEL, passed through to moneo-ask.sh
set -u

TERMUX_HOME=/data/data/com.termux/files/home
if [[ -z "${MONEO_ASK_DIR:-}" ]]; then
  if [[ -d "$TERMUX_HOME" && "$HOME" != "$TERMUX_HOME" ]]; then
    MONEO_ASK_DIR="$TERMUX_HOME/moneo-ask"
  else
    MONEO_ASK_DIR="$HOME/moneo-ask"
  fi
fi
export MONEO_ASK_DIR

WATCHER="$(dirname "$(readlink -f "$0")")/moneo-ask.sh"
STATE="$HOME/.moneo-ask"
PIDFILE="$STATE/watcher.pid"
LOG="$STATE/watcher.log"
mkdir -p "$STATE"

running() { [[ -s "$PIDFILE" ]] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; }

case "${1:-start}" in
  start)
    if running; then
      echo "moneo-ask: already running (pid $(cat "$PIDFILE"))"
      exit 0
    fi
    setsid nohup "$WATCHER" >>"$LOG" 2>&1 </dev/null &
    echo $! >"$PIDFILE"
    sleep 1
    if running; then
      echo "moneo-ask: started (pid $(cat "$PIDFILE")), watching $MONEO_ASK_DIR"
    else
      echo "moneo-ask: failed to start, see $LOG" >&2
      tail -5 "$LOG" >&2
      exit 1
    fi
    ;;
  stop)
    if running; then
      kill -- -"$(cat "$PIDFILE")" 2>/dev/null || kill "$(cat "$PIDFILE")"
      echo "moneo-ask: stopped"
    else
      echo "moneo-ask: not running"
    fi
    rm -f "$PIDFILE"
    ;;
  status)
    if running; then echo "moneo-ask: running (pid $(cat "$PIDFILE"))"; else echo "moneo-ask: not running"; fi
    tail -5 "$LOG" 2>/dev/null
    ;;
  log)
    tail -f "$LOG"
    ;;
  *)
    echo "usage: $0 [start|stop|status|log]" >&2
    exit 2
    ;;
esac
