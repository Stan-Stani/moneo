#!/bin/zsh
# Copy one state file into an app save slot: load_slot.sh <state.bin> <slot 1-3> [serial]
set -e
ADB=(~/Library/Android/sdk/platform-tools/adb ${3:+-s $3})
$ADB push $1 /data/local/tmp/slot.bin >/dev/null
$ADB shell "run-as com.poketrek cp /data/local/tmp/slot.bin files/savestates/slot_$2.bin && echo 4a38a8cb | run-as com.poketrek tee files/savestates/slot_$2.meta >/dev/null; rm /data/local/tmp/slot.bin"
echo "slot $2 <- ${1:t}"
