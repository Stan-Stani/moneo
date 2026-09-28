#!/bin/zsh
# Install a battery save (the game's in-game save) for a ROM, replacing the app's copy.
#   push_save.sh <file.sav> [crc32=4a38a8cb] [serial]
# Stops the app first so the emulator isn't holding the file mapped.
set -e
crc=${2:-4a38a8cb}
ADB=(~/Library/Android/sdk/platform-tools/adb ${3:+-s $3})
$ADB shell am force-stop com.poketrek
$ADB push $1 /data/local/tmp/game.sav >/dev/null
$ADB shell "run-as com.poketrek mkdir -p files/saves && run-as com.poketrek cp /data/local/tmp/game.sav files/saves/$crc.sav; rm /data/local/tmp/game.sav"
echo "files/saves/$crc.sav <- ${1:t}; relaunch the app and choose Continue"
