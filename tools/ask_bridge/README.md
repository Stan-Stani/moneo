# Ask bridge: 💬 in game → Claude Code in Termux

The app's 💬 button sends what's on screen to an LLM through a shared folder:

```
app  → inbox/<id>.png    screenshot (3× nearest-neighbour)
app  → inbox/<id>.json   question, decoded message box text, its words
                         (gloss + known?), every known word, map, ROM
       moneo-ask.sh: claude -p … → outbox/<id>.md
app  ← outbox/<id>.md    shown in the 💬 panel, then deleted
       done/             every request, screenshot and reply, kept
```

A question about the same message as the previous one sets `followUp`, and
the script resumes the previous `claude` session (`.session`) so "why 은
there?" has context. A new message starts a fresh session.

## Setup (Termux on the phone)

```sh
pkg install jq inotify-tools
mkdir -p ~/moneo-ask
cp moneo-ask.sh moneo-ask-start.sh ~/bin/   # or run them from a clone of this repo
moneo-ask-start.sh                          # background watcher; takes a wake lock
```

`moneo-ask-start.sh [start|stop|status|log]` keeps one watcher running in the
background, and `start` does nothing if one already is. To restart it whenever
you open a shell, add `~/bin/moneo-ask-start.sh >/dev/null` to `~/.bashrc`.

**Claude Code in a proot distro** (e.g. Ubuntu under Termux): run both scripts
inside the proot, but keep the folder in Termux's real home, the only place
the app's picker can reach. The start script does this by default when it
sees `/data/data/com.termux/files/home`. `pkg` won't work in the proot, so
install `jq` and `inotify-tools` with its own package manager.

In the app: Settings → "Ask an LLM (💬)" → Choose folder → pick **Termux**
in the picker's side menu → `moneo-ask` → Use this folder. The 💬 button then
appears at the left edge of the game.

The folder can be anywhere the picker can reach, not only Termux: the app
only talks to it through the Storage Access Framework.

Environment: `MONEO_ASK_DIR` (default `~/moneo-ask`), `MONEO_ASK_MODEL`
(passed to `claude --model`, e.g. `sonnet` or `haiku` for quicker replies).
The log shows which model answered and how long it took after each reply.

Exempt Termux from battery optimisation or Android may kill the watcher on
a long walk.
