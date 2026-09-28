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
cp moneo-ask.sh ~/bin/   # or run it from a clone of this repo
moneo-ask.sh             # leave running; takes a wake lock
```

In the app: Settings → "Ask an LLM (💬)" → Choose folder → pick **Termux**
in the picker's side menu → `moneo-ask` → Use this folder. The 💬 button then
appears at the left edge of the game.

The folder can be anywhere the picker can reach, not only Termux: the app
only talks to it through the Storage Access Framework.

Environment: `MONEO_ASK_DIR` (default `~/moneo-ask`), `MONEO_ASK_MODEL`
(passed to `claude --model`, e.g. `sonnet` or `haiku` for quicker replies).

Exempt Termux from battery optimisation or Android may kill the watcher on
a long walk.
